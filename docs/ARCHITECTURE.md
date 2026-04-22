# Architecture

This document describes the structure, invariants, and failure modes of the Fraud Detection Platform. It's meant to be the first thing a new contributor or reviewer reads after the README.

## Contents

1. [System overview](#1-system-overview)
2. [Data flow](#2-data-flow)
3. [Service responsibilities](#3-service-responsibilities)
4. [Topics and schemas](#4-topics-and-schemas)
5. [Data stores](#5-data-stores)
6. [Key invariants](#6-key-invariants)
7. [Failure modes and recovery](#7-failure-modes-and-recovery)
8. [Observability](#8-observability)
9. [Scaling model](#9-scaling-model)
10. [Security posture](#10-security-posture)
11. [Non-goals](#11-non-goals)

## 1. System overview

The platform ingests payment transactions in real time, enriches them with behavioral features, evaluates them through both deterministic rules and a machine-learning model, and emits a decision (`APPROVE` / `REVIEW` / `BLOCK`) with supporting evidence. Historical decisions are queryable for audit.

The high-level shape:

```
Client → Ingestion API → Kafka → Feature Extractor → Kafka
                                          ↓
                                  ┌───────┴───────┐
                                  ↓               ↓
                             Rule Engine     ML Scorer
                                  ↓               ↓
                                  └──────┬────────┘
                                         ↓
                                   Decision Engine → Postgres
                                         ↓
                                  Action Service ← DLQ on failure
```

See [README.md](../README.md) for the rendered diagram.

### Architectural principles

- **Event-driven first.** Services communicate via Kafka topics, not direct calls (except the ML scorer, which is gRPC — see [ADR-0006](./adr/0006-grpc-for-ml.md)). This keeps services independently deployable and failure-isolated.
- **Immutable events.** Every transaction, feature set, and decision is an immutable event. Services derive state by consuming topics; no service mutates another's state directly.
- **Single-responsibility services.** Each service owns one clear concern. Splitting the rule engine and ML scorer (rather than combining them) keeps their failure modes, scaling needs, and iteration cadences independent.
- **Observable by default.** Every service emits metrics, structured logs, and traces. If you can't see it happening, it isn't running.
- **Schema-first.** Avro schemas in `shared/events` are the contract between services. Backward compatibility is enforced by the Schema Registry and CI.

## 2. Data flow

A transaction's journey through the system:

1. **Client** sends `POST /api/v1/transactions` to Ingestion API.
2. **Ingestion API** validates the request, assigns a `transaction_id` if none provided, and publishes to topic `raw-transactions`. Returns `202 Accepted` with the id. *This is fire-and-forget from the client's perspective.*
3. **Feature Extractor** (Kafka Streams) consumes `raw-transactions`, looks up the user's historical features from Redis, computes new features (velocity, amount z-score, geographic delta), writes updated features back to Redis, and emits to `enriched-transactions`.
4. **Rule Engine** and **ML Scorer** both consume `enriched-transactions` in parallel:
   - Rule Engine evaluates a DSL of configured rules and publishes to `rule-verdicts`.
   - ML Scorer runs inference and publishes to `ml-scores`.
5. **Decision Engine** consumes both `rule-verdicts` and `ml-scores`, joins them on `transaction_id` using a Kafka Streams windowed join, combines the signals, writes the final decision to Postgres, and publishes to `decisions`.
6. **Action Service** consumes `decisions` and triggers downstream effects (notifications, block lists). Failures are retried with exponential backoff; poison messages go to `decisions-dlq`.
7. **Query API** exposes historical decisions from Postgres for analyst workflows.

Sequence diagrams for the happy path and common failure scenarios are in [`docs/sequence-diagrams/`](./sequence-diagrams/).

## 3. Service responsibilities

### Ingestion API (`services/ingestion-api`)

Spring Boot 3 REST service. Thin by design: validates, stamps metadata, publishes to Kafka. **Does not** enrich, score, or decide. Exposes OpenAPI at `/swagger-ui.html`.

**SLO:** p99 under 50ms. If this drifts, the fix is almost always not in this service — it's a downstream Kafka cluster issue.

### Feature Extractor (`services/feature-extractor`)

Kafka Streams application. Stateful: holds a keyed state store of per-user rolling aggregates and hydrates it from Redis on startup. See [ADR-0002](./adr/0002-kafka-streams-over-flink.md) for why Kafka Streams and not Flink.

**Features computed:**
- `velocity_1m`, `velocity_1h` — transaction count per time window, per card
- `amount_zscore` — current amount's z-score against the user's 30-day history
- `geo_delta_km` — distance from the user's last transaction location
- `merchant_category_rarity` — 1 / frequency of this MCC for this user

### Rule Engine (`services/rule-engine`)

Spring Boot consumer. Evaluates a DSL of rules loaded at startup from `rules.yaml`. Each rule is a boolean expression over enriched transaction fields; rules are composable via `AND` / `OR` / `NOT`. See [ADR-0003](./adr/0003-rule-engine-dsl.md) for why we built a DSL instead of using Drools.

Rules return `ALLOW`, `REVIEW`, or `DENY` with an explanation. The engine emits the full list of triggered rules, not just the first match — the decision engine uses the full evidence set.

### ML Scorer (`services/ml-scorer`)

Python FastAPI service exposing gRPC. Loads a pickled scikit-learn pipeline (logistic regression on IEEE-CIS features) at startup. Stateless — inference only.

**Why a separate service in a different language:** decoupling the ML iteration cycle from the JVM deployment cycle. Data scientists iterate on the model, retrain offline, and drop in a new pickle without touching Java. See [ADR-0005](./adr/0005-hybrid-ml-rules.md).

### Decision Engine (`services/decision-engine`)

Spring Boot + Kafka Streams. Performs a windowed inner join between `rule-verdicts` and `ml-scores` on `transaction_id`. Applies the combination policy (see [Key invariants](#6-key-invariants)), persists to Postgres, publishes to `decisions`.

### Action Service (`services/action-service`)

Kafka consumer. For each decision, triggers the appropriate action (notify user, call block-list API, etc.). Uses Resilience4j for retry with exponential backoff and circuit breaking. After 5 failed retries, the message moves to the `decisions-dlq` topic for manual inspection.

### Query API (`services/query-api`)

Spring Boot REST service. Read-only access to the decisions table. Paginated, filterable by user, time range, and decision type.

## 4. Topics and schemas

| Topic                     | Partitions | Retention | Producer          | Consumer(s)                |
|---------------------------|-----------:|-----------|-------------------|----------------------------|
| `raw-transactions`        |         12 | 7 days    | ingestion-api     | feature-extractor          |
| `enriched-transactions`   |         12 | 7 days    | feature-extractor | rule-engine, ml-scorer     |
| `rule-verdicts`           |         12 | 7 days    | rule-engine       | decision-engine            |
| `ml-scores`               |         12 | 7 days    | ml-scorer         | decision-engine            |
| `decisions`               |         12 | 30 days   | decision-engine   | action-service, query-api  |
| `decisions-dlq`           |          3 | 30 days   | action-service    | (manual inspection)        |

All topics are keyed by `transaction_id` for ordering guarantees.

Schemas are in `shared/events/src/main/avro/`. The Schema Registry enforces backward compatibility — old consumers must be able to read new messages. CI runs a compatibility check on every PR that modifies a schema.

## 5. Data stores

- **Kafka** — the system's source of truth for events. 3-broker cluster in production; single broker in Docker Compose.
- **Postgres** — persistent store for decisions and audit trail. Indexed on `(user_id, created_at)` and `transaction_id`. Retention: 2 years (regulatory).
- **Redis** — feature cache for the feature extractor. Tiered TTLs (see [ADR-0004](./adr/0004-feature-cache-ttl.md)): 5 minutes for velocity counters, 24 hours for user baselines, 30 days for device fingerprints.
- **Schema Registry** — Avro schemas, versioned, with BACKWARD compatibility enforced.

## 6. Key invariants

These must hold at all times; violations are bugs.

1. **Every transaction gets a decision.** If the pipeline can't produce one, the transaction lands in the DLQ with enough context to manually resolve. There are no silent drops.
2. **Decisions are deterministic given inputs.** Rule and ML outputs for the same `enriched-transaction` must be reproducible. This is what makes audits possible.
3. **Idempotency on ingestion.** Submitting the same `transaction_id` twice results in one decision, not two. Enforced via the Schema Registry's unique constraint on `(transaction_id, submitted_at)`.
4. **No PII in logs.** Card numbers, CVVs, and full user names never appear in logs or metrics. Enforced via a log filter in `common-lib`.
5. **Rule verdicts are pure functions.** Rules cannot make external calls, read databases, or depend on time. This is what lets us run them in parallel with the ML scorer without coordination.
6. **Decision combining policy:** if **any** rule returns `DENY`, the transaction is `BLOCK`ed regardless of ML score. If no rule denies and ML probability exceeds `BLOCK_THRESHOLD` (default 0.9), `BLOCK`. If ML probability exceeds `REVIEW_THRESHOLD` (default 0.5), `REVIEW`. Otherwise `APPROVE`. Rules always win — this is an intentional conservative bias.

## 7. Failure modes and recovery

### Feature extractor crashes

Kafka Streams preserves offset on consumer groups. On restart, the state store is rehydrated from its changelog topic and processing resumes from the last committed offset. In-flight transactions may be processed twice — downstream services are idempotent on `transaction_id`.

### ML scorer unavailable

The decision engine's windowed join has a configurable timeout (default 5s). If no ML score arrives within the window, the decision engine emits a decision using the rule verdict alone, marked with `ml_timeout: true`. The transaction still gets a decision.

### Redis unavailable

Feature extractor falls back to in-memory state, accepting the reduced accuracy (no cross-partition feature sharing). A `redis_degraded` metric fires an alert. No transactions are dropped.

### Postgres unavailable

Decision engine buffers decisions to Kafka (they're already on `decisions` topic). When Postgres recovers, a replay job reads from `decisions` from the last persisted offset. Query API returns a 503 with a Retry-After header.

### Schema compatibility break

Schema Registry rejects the producer's publish. Producer service fails health checks, load balancer pulls it out of rotation. Fix: revert the schema change and redeploy. This is why compatibility is CI-enforced.

## 8. Observability

Every service exposes:

- **Metrics** at `/actuator/prometheus` (Micrometer). Standard RED metrics (rate, errors, duration) per endpoint; business metrics (decisions/sec by outcome, fraud rate, DLQ depth).
- **Logs** in JSON format, shipped to stdout, scraped by your log aggregator of choice. Logs include `trace_id` and `transaction_id` for correlation.
- **Traces** via OpenTelemetry, sampled at 10% in production. Propagated through Kafka headers.

Pre-built Grafana dashboards in `infra/grafana/dashboards/`:

- **Pipeline overview** — throughput, latency percentiles, fraud rate over time
- **Per-service health** — JVM metrics, GC pauses, consumer lag
- **Feature distributions** — distribution of computed features, drift detection
- **Rule effectiveness** — trigger rate per rule, false-positive proxy via override rate

## 9. Scaling model

- **Ingestion API:** stateless, scale horizontally behind a load balancer. Capacity is Kafka-bound, not CPU-bound.
- **Feature Extractor:** scale by adding instances up to the partition count (12). Beyond that, repartition.
- **Rule Engine, ML Scorer, Decision Engine:** same — partition-bound scaling.
- **Postgres:** read replicas for Query API; writes remain on the primary. If decision volume exceeds ~10k/sec sustained, consider sharding by `user_id`.

Load test results in `scripts/load-test.sh` output show the current single-node limit at ~8k TPS.

## 10. Security posture

- **Transport:** TLS between all services. mTLS between services in production.
- **Authentication:** API keys on the ingestion API (pluggable with OAuth2 Resource Server). Internal services authenticate to Kafka via SASL.
- **Authorization:** Query API enforces role-based access — only `ANALYST` and `ADMIN` roles can read decisions.
- **Secrets:** never in code or config. Pulled from environment variables injected by the orchestrator (Kubernetes secrets, AWS Secrets Manager).
- **PII handling:** card numbers are tokenized by the ingestion API immediately on receipt; the downstream pipeline only sees tokens.

## 11. Non-goals

What this system **intentionally does not** do:

- **Not a payment processor.** It scores transactions submitted by upstream systems; it does not move money.
- **Not a case management system.** It emits decisions; human-in-the-loop review is a separate system that consumes the `decisions` topic.
- **Not a model training platform.** Training happens offline in notebooks; only inference is online. Bringing training online (Feast, Airflow, etc.) is roadmap, not current.
- **Not multi-region.** Single-region deployment. Cross-region is roadmap if ever needed.
