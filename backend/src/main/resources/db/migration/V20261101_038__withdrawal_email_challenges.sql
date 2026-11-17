CREATE TABLE IF NOT EXISTS withdrawal_email_challenges (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    email_hash VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    used_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_withdrawal_challenge_token UNIQUE (token_hash),
    CONSTRAINT fk_withdrawal_challenge_user FOREIGN KEY (user_id) REFERENCES app_users(id)
);
CREATE INDEX IF NOT EXISTS idx_withdrawal_challenge_user ON withdrawal_email_challenges (user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_withdrawal_challenge_expires ON withdrawal_email_challenges (expires_at);
