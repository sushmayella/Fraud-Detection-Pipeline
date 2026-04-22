-- Schema for the fraud detection platform.
-- This runs automatically on first container start via docker-entrypoint-initdb.d.

CREATE TABLE IF NOT EXISTS decisions (
    transaction_id       TEXT         PRIMARY KEY,
    user_id              TEXT         NOT NULL,
    outcome              TEXT         NOT NULL CHECK (outcome IN ('APPROVE', 'REVIEW', 'BLOCK')),
    ml_probability       DOUBLE PRECISION,
    ml_timeout           BOOLEAN      NOT NULL DEFAULT FALSE,
    triggered_rules      JSONB        NOT NULL DEFAULT '[]'::jsonb,
    decided_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_decisions_user_time ON decisions (user_id, decided_at DESC);
CREATE INDEX IF NOT EXISTS idx_decisions_outcome   ON decisions (outcome);
CREATE INDEX IF NOT EXISTS idx_decisions_decided   ON decisions (decided_at DESC);

-- Seed a couple of rows so the Query API demo has something to return immediately.
INSERT INTO decisions (transaction_id, user_id, outcome, ml_probability, triggered_rules)
VALUES
    ('seed-txn-001', 'demo-user-1', 'APPROVE', 0.04, '[]'::jsonb),
    ('seed-txn-002', 'demo-user-2', 'BLOCK',   0.94,
     '[{"ruleId":"high_velocity","verdict":"DENY","explanation":"6 txns in 60s"}]'::jsonb)
ON CONFLICT (transaction_id) DO NOTHING;
