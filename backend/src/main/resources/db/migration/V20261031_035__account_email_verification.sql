CREATE TABLE IF NOT EXISTS account_email_verifications (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    target_email VARCHAR(254) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    used_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_account_email_verification_hash UNIQUE (token_hash),
    CONSTRAINT fk_account_email_verification_user FOREIGN KEY (user_id) REFERENCES app_users(id)
);
CREATE INDEX IF NOT EXISTS idx_account_email_verification_user ON account_email_verifications (user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_account_email_verification_expires ON account_email_verifications (expires_at);
