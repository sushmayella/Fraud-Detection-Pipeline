# Sequence diagrams

Happy-path and failure-mode flows for the key pipeline scenarios. Rendered with Mermaid.

## 1. Happy path — transaction reaches a decision

```mermaid
sequenceDiagram
    participant Client
    participant Ingest as Ingestion API
    participant K as Kafka
    participant FE as Feature Extractor
    participant Redis
    participant RE as Rule Engine
    participant ML as ML Scorer
    participant DE as Decision Engine
    participant PG as Postgres
    participant AS as Action Service

    Client->>Ingest: POST /transactions
    Ingest->>Ingest: validate + stamp txn_id
    Ingest->>K: produce raw-transactions
    Ingest-->>Client: 202 Accepted (txn_id)

    K->>FE: consume raw-transactions
    FE->>Redis: read user baseline
    FE->>FE: compute features
    FE->>Redis: write updated features
    FE->>K: produce enriched-transactions

    par Rule evaluation
        K->>RE: consume enriched
        RE->>RE: evaluate rules
        RE->>K: produce rule-verdicts
    and ML scoring
        K->>ML: consume enriched
        ML->>ML: inference
        ML->>K: produce ml-scores
    end

    DE->>K: join rule-verdicts + ml-scores
    DE->>DE: combine → decision
    DE->>PG: persist decision
    DE->>K: produce decisions

    K->>AS: consume decisions
    AS->>AS: trigger downstream actions
```

## 2. ML scorer timeout — decision made on rule verdict alone

```mermaid
sequenceDiagram
    participant K as Kafka
    participant RE as Rule Engine
    participant ML as ML Scorer
    participant DE as Decision Engine

    K->>RE: enriched-transactions
    K->>ML: enriched-transactions
    RE->>K: rule-verdict (within 50ms)
    Note over ML: ML service slow or down
    DE->>DE: windowed join — wait 5s for ml-score
    DE->>DE: timeout — fallback policy
    DE->>K: decision (marked ml_timeout=true)
```

## 3. Action service failure with retry + DLQ

```mermaid
sequenceDiagram
    participant K as Kafka
    participant AS as Action Service
    participant Ext as External System
    participant DLQ as decisions-dlq

    K->>AS: decision
    AS->>Ext: call external block-list API
    Ext-->>AS: 500 error
    Note over AS: Resilience4j retry<br/>exponential backoff
    AS->>Ext: retry 1 (250ms later)
    Ext-->>AS: 500 error
    AS->>Ext: retry 2 (500ms later)
    Ext-->>AS: 500 error
    AS->>Ext: retry 3 (1s later)
    Ext-->>AS: 500 error
    AS->>Ext: retry 4 (2s later)
    Ext-->>AS: 500 error
    AS->>Ext: retry 5 (4s later)
    Ext-->>AS: 500 error
    AS->>DLQ: publish with failure context
```

## 4. Idempotent replay — same transaction submitted twice

```mermaid
sequenceDiagram
    participant Client
    participant Ingest as Ingestion API
    participant K as Kafka
    participant DE as Decision Engine
    participant PG as Postgres

    Client->>Ingest: POST /transactions (txn_id=abc)
    Ingest->>K: produce raw-transactions
    Ingest-->>Client: 202 Accepted

    Note over Client,Ingest: Client retries (network blip)

    Client->>Ingest: POST /transactions (txn_id=abc)
    Ingest->>K: produce raw-transactions
    Ingest-->>Client: 202 Accepted

    Note over K,DE: Both events flow through pipeline

    DE->>PG: INSERT decision (txn_id=abc) ON CONFLICT DO NOTHING
    DE->>PG: INSERT decision (txn_id=abc) ON CONFLICT DO NOTHING

    Note over PG: Second insert is a no-op.<br/>Exactly one decision exists.
```

## 5. Feature extractor restart — state recovery

```mermaid
sequenceDiagram
    participant FE as Feature Extractor (crashed)
    participant K as Kafka
    participant CL as Changelog Topic
    participant RocksDB as Local RocksDB

    Note over FE: Instance starts
    FE->>K: rejoin consumer group
    K->>FE: assign partitions 0-3
    FE->>CL: read changelog for partitions 0-3
    CL->>RocksDB: replay into local state store
    Note over FE,RocksDB: State fully restored
    FE->>K: resume consuming from last committed offset
```
