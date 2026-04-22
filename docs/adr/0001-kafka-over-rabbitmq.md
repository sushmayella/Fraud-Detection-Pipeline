# ADR-0001: Kafka over RabbitMQ as the event backbone

- **Status:** Accepted
- **Date:** 2025-11-04
- **Deciders:** @core-team

## Context

The platform is event-driven: every service communicates by producing and consuming messages on shared topics. We need a backbone that supports:

1. **Ordered processing per key** — transactions for the same card must be processed in order.
2. **Replayability** — the feature extractor and decision engine must be able to rebuild state by re-reading historical events during recovery or backfill.
3. **Independent consumer scaling** — rule engine and ML scorer consume the same topic at different rates; neither should block the other.
4. **Throughput headroom** — the system is designed for ~10k TPS sustained, with bursts to 30k.
5. **Schema evolution** — producers and consumers deploy on independent cadences; schema changes must not cause outages.

## Decision

We will use **Apache Kafka** (with Confluent Schema Registry for Avro schemas) as the event backbone for all inter-service communication except the decision engine ↔ ML scorer path, which uses gRPC (see [ADR-0006](./0006-grpc-for-ml.md)).

## Alternatives considered

### RabbitMQ

**Pros:** mature, excellent operational tooling, lower operational burden than Kafka, great for work queues.

**Cons:**
- Once a message is consumed it's gone — no replay. Recovery requires external persistence or a separate event store.
- Consumer groups aren't a first-class primitive; competing consumers require queue topology gymnastics.
- Partitioned ordering is not native.

**Rejected because:** the replay and partitioned-ordering requirements are load-bearing for our architecture. Engineering around RabbitMQ to get them would recreate Kafka badly.

### AWS SQS + SNS

**Pros:** fully managed, no operational burden, good for fan-out patterns.

**Cons:**
- Message retention capped at 14 days.
- No native ordering across a topic — FIFO queues exist but max 300 TPS per queue.
- Vendor lock-in; we want the platform to be portable.

**Rejected because:** retention and ordering constraints don't meet requirements 1 and 2.

### AWS Kinesis

**Pros:** managed Kafka-like semantics.

**Cons:**
- Shard limits (1 MB/s write, 2 MB/s read per shard) require careful capacity planning.
- Ecosystem around Kafka Streams is much richer than around Kinesis Client Library.
- Kafka Streams specifically is a key part of our feature-extraction design.

**Rejected because:** our stream-processing tier (ADR-0002) assumes Kafka Streams, which doesn't support Kinesis.

### Redpanda

**Pros:** Kafka wire-protocol compatible, no JVM, lower operational footprint, faster per-broker.

**Cons:**
- Smaller ecosystem and fewer proven production deployments at our target scale.
- Team's Kafka experience translates directly; Redpanda has subtle operational differences.

**Rejected because:** Kafka's maturity and team familiarity outweigh Redpanda's performance advantages at our current scale. Worth revisiting if operational burden becomes a pain point.

## Consequences

### Easier

- Event replay for recovery and backfill is native (`--from-beginning`).
- Kafka Streams becomes available as a stream-processing option — we'll lean on this in ADR-0002.
- Consumer group scaling is a well-worn path; on-call runbooks are established.
- Schema Registry + Avro gives us enforced compatibility and compact serialization.

### Harder

- Kafka has real operational burden — broker tuning, rebalance storms, offset management. We commit to investing in operational tooling (Kafka UI, lag alerts, rebalance monitoring).
- Exactly-once semantics require careful configuration across producers, consumers, and streams. We document patterns in `docs/kafka-patterns.md`.
- Local development needs Docker Compose with Kafka + Schema Registry running; this raises the RAM floor for contributors to ~8 GB.

### New risks

- **Consumer lag** during traffic spikes. Mitigated via horizontal scaling up to partition count and alerts on lag > 30s.
- **Schema Registry as SPOF.** Mitigated in production by running it HA; in local dev we accept the risk.
- **Topic sprawl.** We commit to reviewing topic creation in ADRs or design docs, not ad hoc.

## References

- [Kafka: The Definitive Guide](https://www.confluent.io/resources/kafka-the-definitive-guide/) — chapters 3 and 4 inform our producer/consumer config defaults.
- [Confluent's schema evolution rules](https://docs.confluent.io/platform/current/schema-registry/avro.html)
- Related: [ADR-0002](./0002-kafka-streams-over-flink.md), [ADR-0006](./0006-grpc-for-ml.md)
