-- V1: complete v1 schema, taken verbatim from docs/spec.md §5.1 (v1.2).
-- Status semantics are enforced in code (no CHECK constraints / enum types by design).

CREATE TABLE reviews (
    id                 BIGSERIAL PRIMARY KEY,
    installation_id    BIGINT       NOT NULL,
    repo_full_name     TEXT         NOT NULL,
    pr_number          INT          NOT NULL,
    head_sha           TEXT         NOT NULL,
    round              INT          NOT NULL DEFAULT 1,   -- re-review discriminator (C-4)
    status             TEXT         NOT NULL,  -- PROCESSING | POSTING(v2) | AWAITING_RETRY
                                               -- | SUCCEEDED | FAILED | SKIPPED
    fence              BIGINT       NOT NULL DEFAULT 0,   -- [v2] monotonic claim token
    error_attempts     INT          NOT NULL DEFAULT 0,
    defer_attempts     INT          NOT NULL DEFAULT 0,
    failure_class      TEXT,                                -- terminal failure class, OR skip reason on
                                                            -- SKIPPED rows (PR_CLOSED, SUSPENDED,
                                                            -- AUTHOR_NOT_PERMITTED, ...)
    github_review_id   BIGINT,                              -- returned by GitHub on post
    post_attempted_at  TIMESTAMPTZ,                         -- set immediately before POST (§13.2 guard)
    first_received_at  TIMESTAMPTZ  NOT NULL,
    first_deferred_at  TIMESTAMPTZ,                          -- set on first DEFER; SLA clock (C-3)
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at       TIMESTAMPTZ,
    UNIQUE (installation_id, repo_full_name, pr_number, head_sha, round)
);
CREATE INDEX idx_reviews_rate ON reviews (installation_id, status);           -- rate-limit count
CREATE INDEX idx_reviews_last ON reviews (installation_id, repo_full_name,
                                          pr_number, completed_at DESC)
    WHERE status = 'SUCCEEDED';                                               -- incremental base lookup

CREATE TABLE installations (
    installation_id    BIGINT PRIMARY KEY,
    api_key_encrypted  BYTEA,           -- AES-GCM via KMS data key; NEVER plaintext, NEVER logged
    default_model      TEXT NOT NULL DEFAULT 'claude-sonnet-4-6',
    suspended          BOOLEAN NOT NULL DEFAULT false,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE dlq_entries (                                   -- DLQ indexer target (§10.3)
    id               BIGSERIAL PRIMARY KEY,
    envelope         JSONB NOT NULL,                         -- full §10.2 envelope
    failure_class    TEXT  NOT NULL,
    repo_full_name   TEXT,
    pr_number        INT,
    head_sha         TEXT,                                   -- nullable (POISON has no identity)
    installation_id  BIGINT,                                 -- nullable for POISON (D-2)
    dlq_partition    INT NOT NULL,
    dlq_offset       BIGINT NOT NULL,
    status           TEXT NOT NULL DEFAULT 'NEW',            -- NEW | REPLAYED | DISCARDED
    resolved_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (dlq_partition, dlq_offset)                       -- indexer crash-safe (D-5)
);
CREATE INDEX idx_dlq_status ON dlq_entries (status, failure_class);
CREATE INDEX idx_dlq_tenant ON dlq_entries (installation_id, failure_class);  -- flood triage (P2-5)
