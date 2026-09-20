ALTER TABLE app_users
    ADD COLUMN IF NOT EXISTS email VARCHAR(254) NULL,
    ADD COLUMN IF NOT EXISTS email_verified BOOLEAN NOT NULL DEFAULT TRUE;

CREATE UNIQUE INDEX IF NOT EXISTS uk_app_users_email ON app_users (email);

CREATE TABLE IF NOT EXISTS email_verification_tokens (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    used_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_email_verification_tokens_hash UNIQUE (token_hash),
    CONSTRAINT fk_email_verification_tokens_user FOREIGN KEY (user_id) REFERENCES app_users (id)
);

CREATE INDEX IF NOT EXISTS idx_email_verification_tokens_user
    ON email_verification_tokens (user_id, used_at);

CREATE INDEX IF NOT EXISTS idx_email_verification_tokens_expires
    ON email_verification_tokens (expires_at);

CREATE TABLE IF NOT EXISTS social_accounts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    provider VARCHAR(30) NOT NULL,
    provider_user_id VARCHAR(128) NOT NULL,
    email VARCHAR(254) NULL,
    linked_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_social_accounts_provider_subject UNIQUE (provider, provider_user_id),
    CONSTRAINT fk_social_accounts_user FOREIGN KEY (user_id) REFERENCES app_users (id)
);

CREATE INDEX IF NOT EXISTS idx_social_accounts_user_id ON social_accounts (user_id);
