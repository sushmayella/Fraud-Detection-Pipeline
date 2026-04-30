# Architecture Decision Records

This directory contains the ADRs for the Fraud Detection Platform. ADRs document the *why* behind significant design decisions so that the reasoning survives after the original authors move on.

## Index

| #    | Title                                                               | Status   |
|------|---------------------------------------------------------------------|----------|
| 0001 | [Kafka over RabbitMQ as the event backbone](./0001-kafka-over-rabbitmq.md) | Accepted |
| 0002 | [Kafka Streams over Flink for stream processing](./0002-kafka-streams-over-flink.md) | Accepted |
| 0003 | [In-house rule DSL over Drools](./0003-rule-engine-dsl.md)          | Accepted |
| 0004 | [Event-time windowing for velocity features](./0004-event-time-windowing.md) | Accepted |
| 0005 | Redis feature cache with tiered TTLs *(planned)*                    | —        |
| 0006 | Hybrid ML + rules decisioning strategy *(planned)*                  | —        |
| 0007 | gRPC between decision engine and ML scorer *(planned)*              | —        |

## Writing a new ADR

1. Copy `0000-template.md` to `NNNN-short-title.md` with the next available number.
2. Fill in the sections; prune the ones you don't need.
3. Open a PR. ADRs are reviewed like code.
4. Once merged, ADRs are **immutable**. If a later decision reverses an earlier one, write a new ADR with `Status: Supersedes ADR-NNNN` and update the old one's status to `Superseded by ADR-MMMM`.

## Why ADRs?

> "Every architectural decision is also a cultural artifact. Decisions that are not written down, or written down poorly, are re-litigated indefinitely."

The goal isn't formality — it's saving your future self and teammates from arguing the same tradeoffs repeatedly. An ADR worth writing is one where, six months later, someone asks "why did we do it this way?" and you wish you had a good answer.
