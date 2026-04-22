# Fraud Detection Platform

> Real-time, event-driven fraud detection pipeline. Transactions flow through feature extraction, rule evaluation, and ML scoring to produce auditable decisions with sub-200ms p99 latency.

[![CI](https://github.com/sushmayella/Fraud-Detection-Pipeline/actions/workflows/ci.yml/badge.svg)](https://github.com/sushmayella/Fraud-Detection-Pipeline/actions/workflows/ci.yml)
![Java 17](https://img.shields.io/badge/java-17-blue)
![Spring Boot 3.2](https://img.shields.io/badge/spring--boot-3.2-brightgreen)
![Kafka 3.6](https://img.shields.io/badge/kafka-3.6-black)
![License](https://img.shields.io/badge/license-MIT-blue)

## What this is

A production-style fraud detection system built around Kafka, Kafka Streams, and a hybrid rules-plus-ML decisioning model. It's designed as a learning and portfolio project — the architecture mirrors patterns you'd find at payments companies like Stripe, Visa, or Capital One, scaled down to fit on a developer laptop via Docker Compose.

**Core ideas:**

- **Event-driven.** Services communicate via Kafka topics, not direct calls. Every transaction, feature set, and decision is an immutable, replayable event.
- **Hybrid decisioning.** Deterministic rules catch known fraud patterns; ML scoring catches novel ones. A combiner policy fuses both signals (see [ADR-0005](./docs/adr/)).
- **Auditable.** Every decision records which rules triggered, what the ML probability was, and what features fed into it. No black boxes.
- **Observable.** Metrics, traces, and structured logs from every service. Pre-built Grafana dashboards.

## Architecture

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
                                   Action Service
```

See [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md) for invariants, failure modes, the full component breakdown, and scaling notes. See [`docs/sequence-diagrams/`](./docs/sequence-diagrams/) for the happy-path, ML-timeout, and DLQ flows.

## Quick start

```bash
# 1. Clone and bootstrap
git clone https://github.com/sushmayella/Fraud-Detection-Pipeline.git
cd Fraud-Detection-Pipeline
mvn -pl shared/events install -DskipTests

# 2. Start the infrastructure and ingestion service
docker-compose up -d

# 3. Submit a test transaction
curl -X POST http://localhost:8080/api/v1/transactions \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "user-42",
    "cardFingerprint": "fp_abc123",
    "amount": 249.99,
    "currency": "USD",
    "merchantId": "m-anchor-1",
    "merchantCategoryCode": 5411,
    "country": "US"
  }'

# 4. Watch it hit Kafka
open http://localhost:8090   # Kafka UI
```

**Service URLs** once the stack is up:

| Service            | URL                                      | Notes                     |
|--------------------|------------------------------------------|---------------------------|
| Ingestion API      | http://localhost:8080/swagger-ui.html    | OpenAPI docs              |
| Kafka UI           | http://localhost:8090                    | Topic browser             |
| Schema Registry    | http://localhost:8081                    | Avro schemas              |
| Prometheus         | http://localhost:9090                    | Raw metrics               |
| Grafana            | http://localhost:3000 (admin/admin)      | Pre-built dashboards      |
| Postgres           | `localhost:5432` (fraud/fraud_local)     |                           |

## Project status

This repo is being built out incrementally. The roadmap is honest about what's shipped vs in progress.

### Shipped (phase 1)

- [x] Ingestion API — Spring Boot 3, Avro + Schema Registry, idempotent Kafka producer
- [x] Shared Avro event schemas (`Transaction`, `EnrichedTransaction`, `Decision`)
- [x] Full project scaffolding — Maven multi-module, Docker Compose stack, GitHub Actions CI
- [x] Integration testing with Testcontainers
- [x] OpenAPI / Swagger UI
- [x] Architecture docs + 3 ADRs
- [x] JaCoCo coverage gate at 80%

### In progress (phase 2)

- [ ] **Feature Extractor** — Kafka Streams topology for velocity, z-score, and geo-delta features
- [ ] **Rule Engine** — YAML DSL evaluator ([ADR-0003](./docs/adr/0003-rule-engine-dsl.md))
- [ ] **ML Scorer** — Python FastAPI service with logistic regression on IEEE-CIS dataset
- [ ] **Decision Engine** — Kafka Streams windowed join, Postgres persistence
- [ ] **Query API** — read-only REST API for historical decisions

### Planned (phase 3)

- [ ] Action Service with Resilience4j retry + DLQ
- [ ] Grafana dashboards (pipeline, per-service, feature distribution)
- [ ] Load-test harness with k6
- [ ] Kubernetes manifests + Helm chart

## Tech stack

- **JVM:** Java 17, Spring Boot 3.2, Spring Kafka
- **Streaming:** Kafka 3.6, Kafka Streams, Confluent Schema Registry, Avro
- **Data:** PostgreSQL 16, Redis 7
- **ML:** Python 3.11, FastAPI, scikit-learn *(phase 2)*
- **Observability:** Micrometer, Prometheus, Grafana, OpenTelemetry
- **Build & CI:** Maven, Docker, GitHub Actions, JaCoCo (80% coverage gate), Spotless
- **Testing:** JUnit 5, AssertJ, Mockito, Testcontainers, Kafka Streams `TopologyTestDriver`

## Design decisions

Architecture Decision Records in [`docs/adr/`](./docs/adr/) document the *why* behind key choices:

- [**ADR-0001**](./docs/adr/0001-kafka-over-rabbitmq.md) — Kafka over RabbitMQ as the event backbone
- [**ADR-0002**](./docs/adr/0002-kafka-streams-over-flink.md) — Kafka Streams over Flink for stream processing
- [**ADR-0003**](./docs/adr/0003-rule-engine-dsl.md) — In-house rule DSL over Drools

## Development

See [`CONTRIBUTING.md`](./CONTRIBUTING.md) for setup, commit conventions, PR expectations, and testing workflow.

```bash
mvn test              # unit tests
mvn verify            # unit + integration (Testcontainers)
mvn spotless:apply    # auto-format
```

## License

MIT — see [`LICENSE`](./LICENSE).

---

Built by [Sushma Yella](https://github.com/sushmayella). Informed by prior work on payments and notifications platforms at Capital One, Visa, and Walmart.
