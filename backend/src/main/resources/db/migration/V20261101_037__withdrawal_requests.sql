-- A request survives account deletion; its user_id is cleared when the workflow is finalized.
CREATE TABLE IF NOT EXISTS account_withdrawal_requests (
    id VARCHAR(36) NOT NULL,
    user_id BIGINT NULL,
    state VARCHAR(30) NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_account_withdrawal_user UNIQUE (user_id)
);
CREATE INDEX IF NOT EXISTS idx_account_withdrawal_state ON account_withdrawal_requests (state, updated_at);
