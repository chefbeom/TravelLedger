-- Durable, coalescing outbox. No foreign keys: deletion tombstones must survive source deletion.
-- Payloads, memos and credentials are intentionally never stored here.
CREATE TABLE IF NOT EXISTS ledger_embedding_sync_jobs (
    owner_id BIGINT NOT NULL,
    record_type VARCHAR(30) NOT NULL,
    source_id BIGINT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    applied_revision BIGINT NOT NULL DEFAULT 0,
    state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    claim_token VARCHAR(36) NULL,
    claimed_revision BIGINT NULL,
    lease_until DATETIME(6) NULL,
    last_error_code VARCHAR(50) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id, record_type, source_id),
    INDEX idx_ledger_embedding_ready (state, next_attempt_at),
    INDEX idx_ledger_embedding_expiry (state, lease_until)
);
