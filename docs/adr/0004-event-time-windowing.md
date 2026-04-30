# ADR-0004: Event-time windowing with 5-second grace for velocity features

- **Status:** Accepted
- **Date:** 2026-04-23
- **Deciders:** @core-team
- **Related:** [ADR-0002](./0002-kafka-streams-over-flink.md)

## Context

The feature extractor computes per-card velocity: how many transactions a card has made in the last 1 minute and 1 hour. Velocity is the single highest-signal feature in our rule set — rapid-fire transactions are one of the most common fraud patterns — so getting the definition right matters.

Three questions surface almost immediately:

1. **What time is "now" when evaluating a window?** Wall-clock time at processing, or the transaction's own timestamp?
2. **How do we handle events that arrive out of order?** A transaction that occurred 3 seconds ago but lands after a transaction that occurred 1 second ago — is that normal, an error, or both?
3. **What window shape?** Tumbling (non-overlapping), hopping (overlapping with an advance interval), or sliding (every point is its own window)?

These are not abstract — the wrong answer to any of them produces a velocity count that silently disagrees with reality. Silent correctness bugs in a fraud pipeline become false BLOCKs (user frustration, lost revenue) or false APPROVEs (fraud losses). Both are expensive.

## Decision

We will use **event-time windowing with a 5-second grace period, over tumbling 1-minute and 1-hour windows**.

Concretely:

- **Time semantic: event time.** Windows are evaluated against the transaction's own `timestamp` field (set by the client or stamped by the ingestion API), not the wall clock at the moment Kafka Streams processes the record.
- **Grace: 5 seconds.** Events whose event time falls within a window that closed up to 5 seconds ago are still accepted and counted. Past 5 seconds, they're dropped from velocity (but still flow through the pipeline for downstream processing).
- **Shape: tumbling.** A 1-minute window covers `[12:00:00, 12:01:00)`. An event at `12:00:59.999` falls in the first window; `12:01:00.000` falls in the second.

## Alternatives considered

### Time semantic

#### Processing time (rejected)
**Pros:** simplest implementation; no need to trust client timestamps; deterministic given a processing order.

**Cons:**
- **Breaks under replay.** Restarting the service or replaying a topic would re-compute velocities against the current wall clock, producing different numbers than what was seen originally. This violates the "decisions are deterministic given inputs" invariant in [ARCHITECTURE.md §6](../ARCHITECTURE.md).
- **Punishes backpressure.** If the pipeline lags for 30 seconds during a traffic spike, all the transactions that hit the feature extractor afterward appear to have happened simultaneously, massively inflating velocity and triggering false fraud blocks across the board.
- **Sensitive to partition assignment.** Two transactions processed by different instances in rapid succession may land on the wrong side of the wall-clock minute boundary.

**Rejected because:** the first two problems are load-bearing. We need deterministic replay for audits and regulatory review.

#### Ingestion time (rejected)
Every record gets a timestamp when it enters the broker, supplied by Kafka itself. This is closer to event time than processing time but still wrong — it stamps the moment the ingestion API committed to the topic, not when the transaction actually occurred. Under backpressure or retry, this diverges from event time by seconds. Same replay and backpressure issues as processing time, just smaller.

**Rejected because:** doesn't solve the core problem, just mitigates it.

#### Event time (chosen)
The transaction's own `timestamp` is the authoritative "when this happened." We trust the ingestion API to stamp it correctly (it does — see `TransactionService`) and use that everywhere downstream.

**Cons to acknowledge:**
- Requires out-of-order handling (the grace period addresses this).
- Depends on upstream stamping being honest. A malicious or buggy client could produce events with timestamps far in the past or future. Mitigated by validating `timestamp` at ingestion (not done in this PR; noted as TODO).

### Grace period

#### No grace (rejected)
Strictly drop any event arriving after its window closed.

**Rejected because:** in practice, producer-side buffering, broker replication, and consumer lag routinely add 100-500ms of latency. A hard cutoff at the window boundary would drop legitimate events under any real load.

#### 30-second grace (rejected)
Generous enough to absorb almost any delay.

**Rejected because:** forces state stores to keep each window open for 30 seconds longer than necessary. At our target scale (10k TPS), that's additional gigabytes of in-memory state and proportional changelog traffic. Also delays window finalization, which matters less for our use case but adds up if we ever compute summary stats.

#### 5-second grace (chosen)
Covers >99% of realistic arrival lateness we've observed in similar pipelines. Bounded state growth. Reasonable balance.

### Window shape

#### Sliding windows (rejected)
Every event is the end of its own 60-second window. Most precise — "in the last 60 seconds from *this* transaction's perspective" is literally what we want.

**Cons:**
- **State cost.** Kafka Streams' sliding window implementation materializes a state-store entry per unique window, which means orders of magnitude more storage than tumbling.
- **Overhead on every event.** Each record triggers a scan over a larger window-store range.
- **Diminishing returns.** The practical difference between sliding and tumbling velocity, for fraud signals, is small — both surface the "6 transactions in a minute" pattern effectively.

**Rejected because:** the precision gain doesn't justify the operational cost at our scale. Revisit if we ever need sub-second precision on velocity.

#### Hopping windows with 10s advance (rejected)
Compromise between tumbling and sliding. Each event falls into 6 overlapping 1-minute windows (one starting every 10 seconds).

**Rejected because:** it's tumbling's state cost × 6 for a small increase in precision. Sliding is more principled if we need that precision; hopping is a middle path we don't have a specific use case for.

#### Tumbling windows (chosen)
Aligned on wall-clock boundaries (minute boundaries for 1m, hour boundaries for 1h). One state-store entry per (card, window).

**The known weakness:** a transaction at 12:00:59 and one at 12:01:01 are in different 1-minute windows. Velocity1m for the second one appears to "reset" to 1. A sophisticated fraudster could time transactions around window boundaries to evade velocity-based rules.

**Why we accept this:** (a) velocity1h covers the boundary gap — the second transaction shows velocity1h=2. Our rules can use both. (b) real fraud patterns are bursty and rarely timed to the second. (c) if we ever see evidence of boundary-timing attacks, we can add a second tumbling window offset by 30 seconds as a cheap mitigation, or switch to sliding.

## Consequences

### Easier

- **Deterministic replay.** A topic replay produces identical velocity counts, which makes decisions auditable. Required for financial compliance.
- **Bounded state.** Tumbling + 5s grace means state for any given window is retained for at most `window_size + grace`. Memory and disk usage are predictable.
- **Backpressure resilience.** A 30-second processing pause doesn't distort velocity numbers. Events still have correct event times when they eventually flow through.
- **Testable with TopologyTestDriver.** Event-time windowing is exactly what `TopologyTestDriver` lets us simulate precisely — we pipe records with specific timestamps and assert on the resulting counts. See `FeatureExtractorTopologyTest`.

### Harder

- **Trust in client timestamps.** A transaction with a clock-skewed timestamp produces wrong velocity. Mitigation plan: add timestamp validation at ingestion (reject events more than 10 minutes off wall clock) in a follow-up PR.
- **Window-boundary artifacts.** Transactions straddling the minute mark produce the "velocity1m resets" effect described above. Documented and accepted; re-evaluate if real attacks emerge.
- **Grace period is a magic number.** 5 seconds is our best guess at observed production latency. If real lateness distribution has a long tail, we'll see dropped late events in the metrics. Tracked via the `kafka.streams.skipped-records-total` metric.

### New risks

- **Stream-time stalls.** Kafka Streams advances "stream time" based on the maximum event timestamp observed. If one partition receives no traffic, its stream time doesn't advance and windows there stay open past their logical close. Low-volume cards would technically see this, but velocity is meaningful only when there *is* traffic, so the practical impact is zero. Monitored via lag metrics.
- **Clock skew in production data.** If we see `skipped-records-total` climb, investigate upstream client clocks before changing grace.

## Revisit triggers

We should reconsider this decision if:

- Fraud analysts identify a pattern that exploits window-boundary artifacts.
- Observed arrival-time lateness regularly exceeds 5 seconds (grace adjustment).
- Scale pushes state-store size for tumbling to an operational problem (unlikely at current throughput).
- We need sub-second velocity precision for a new rule (move to sliding).

## References

- [Kafka Streams time semantics](https://kafka.apache.org/documentation/streams/core-concepts#streams_time)
- [Confluent on windowing and grace](https://docs.confluent.io/platform/current/streams/developer-guide/dsl-api.html#windowing)
- Related: [ADR-0002](./0002-kafka-streams-over-flink.md) (Kafka Streams over Flink)
