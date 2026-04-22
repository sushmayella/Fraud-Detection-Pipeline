# ADR-0002: Kafka Streams over Flink for stream processing

- **Status:** Accepted
- **Date:** 2025-11-06
- **Deciders:** @core-team

## Context

The feature extractor is the stateful heart of the pipeline. It needs to:

1. Maintain **per-user rolling windows** (1-minute and 1-hour transaction counts) with exactly-once semantics.
2. Compute **per-user baseline statistics** (30-day rolling mean and stddev of transaction amounts).
3. Join **transactions with user profile data** (keyed on `user_id`) loaded from a compacted topic.
4. Be **operationally simple** — the team is 1–3 engineers; we cannot afford a dedicated stream-processing platform team.
5. Scale with **partition-level parallelism** to meet our 10k TPS target.

Similar stateful processing happens in the decision engine (windowed join between `rule-verdicts` and `ml-scores`), so whatever we pick is used in at least two places.

## Decision

We will use **Kafka Streams** (the JVM library embedded in each service) for all stream-processing workloads.

## Alternatives considered

### Apache Flink

**Pros:**
- More powerful: true event-time processing, richer windowing, better support for complex event processing patterns.
- Better for very large state (many TB) via RocksDB + incremental checkpointing to S3.
- Excels at batch + streaming unification.

**Cons:**
- Separate cluster to operate (JobManager + TaskManagers). For our team size, this is a significant fixed cost.
- Deployment model is "submit a job" rather than "deploy a service." Integration with our microservice CI/CD is awkward.
- Local development requires a Flink cluster in Docker Compose, pushing the memory floor higher.
- State recovery after a JobManager failure is more complex than Kafka Streams' per-instance model.

**Rejected because:** operational burden outweighs the feature advantages at our scale. If we ever need multi-TB state or true cross-stream event-time joins with out-of-order tolerance, we'll revisit.

### Apache Spark Structured Streaming

**Pros:** same codebase as batch, familiar DataFrame API, good for analysts.

**Cons:**
- Micro-batch model adds latency. Our p99 target for end-to-end processing is 200ms; Spark's minimum trigger interval pushes that to seconds.
- Checkpointing to HDFS/S3 is heavier than Kafka Streams' changelog-topic model.

**Rejected because:** latency floor is incompatible with our SLO.

### Hand-rolled consumer applications

**Pros:** maximum control, zero additional framework to learn.

**Cons:**
- We'd rebuild windowing, state stores, rebalance coordination, and changelog persistence ourselves. All of these are subtly hard.
- Code reviewers spend their time on our framework bugs instead of our business logic.

**Rejected because:** Kafka Streams is the hand-rolled solution, productionized by people who know Kafka's internals better than we do.

### Materialize / RisingWave (streaming SQL)

**Pros:** declarative SQL model, great for analysts.

**Cons:**
- New and less proven at our target scale; smaller community.
- Team has zero experience; learning curve doesn't pay off for the amount of stream processing we do.

**Rejected because:** risk and unfamiliarity outweigh the productivity gain for the amount of streaming logic we have.

## Consequences

### Easier

- **Deployment is trivial.** Each Kafka Streams app is a Spring Boot service; it deploys like any other. No separate cluster.
- **Local dev is lightweight.** The Streams app runs in-process; no cluster needed.
- **Scaling story is simple.** More instances = more parallelism, up to partition count.
- **State is colocated with processing.** RocksDB state stores on local disk, fault-tolerant via changelog topics. Recovery is automatic on rebalance.
- **Testability is excellent.** `TopologyTestDriver` gives synchronous, in-process testing of the full topology with no external dependencies. See `services/feature-extractor/src/test/`.

### Harder

- **Scaling is capped by partition count** (12 in our current config). Rebalancing beyond that requires a partition-count increase, which is operationally disruptive.
- **No true event-time processing** across multiple streams with wildly varying lateness. Our windows use processing-time or ingestion-time. For fraud detection, the window sizes (seconds to minutes) make this acceptable.
- **State migrations are delicate.** Changing a state store's schema requires either a topology reset or a careful migration path. We commit to treating state-store schemas with the same discipline as database migrations.

### New risks

- **Rebalance storms** on deploy or instance death can cause brief processing pauses. Mitigated by static consumer group membership and incremental cooperative rebalancing (enabled in config).
- **RocksDB memory growth** if state stores grow unbounded. Mitigated by TTLs on state-store records and monitoring.
- **Tight coupling to Kafka.** If we ever change event backbone (see ADR-0001), the stream-processing layer rewrites too.

## Revisit triggers

We should reconsider this decision if:

- State size exceeds ~100 GB per instance.
- We need to join streams with >10 minute out-of-order tolerance.
- We add a dedicated streaming/analytics team who can operate Flink well.
- Business requirements push us into complex CEP patterns (sequence detection across long windows).

## References

- [Kafka Streams Architecture](https://kafka.apache.org/documentation/streams/architecture)
- [Flink vs Kafka Streams comparison](https://www.confluent.io/blog/kafka-streams-vs-apache-flink/)
- Related: [ADR-0001](./0001-kafka-over-rabbitmq.md)
