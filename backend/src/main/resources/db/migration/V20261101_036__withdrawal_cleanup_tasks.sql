-- Cleanup tasks deliberately have no user foreign key so pending work survives account deletion.
CREATE TABLE IF NOT EXISTS account_withdrawal_cleanup_tasks (
    id VARCHAR(36) NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    kind VARCHAR(40) NOT NULL,
    task_key_hash VARCHAR(43) NOT NULL,
    payload_hash VARCHAR(43) NOT NULL,
    encrypted_payload VARCHAR(4096) NULL,
    state VARCHAR(20) NOT NULL,
    attempts INT NOT NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    claim_token VARCHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    last_error_code VARCHAR(50) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_withdrawal_cleanup_key UNIQUE (request_id, task_key_hash)
);
CREATE INDEX IF NOT EXISTS idx_withdrawal_cleanup_due ON account_withdrawal_cleanup_tasks (state, next_attempt_at);
CREATE INDEX IF NOT EXISTS idx_withdrawal_cleanup_lease ON account_withdrawal_cleanup_tasks (state, lease_until);
