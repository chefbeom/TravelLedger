ALTER TABLE app_users
    ADD COLUMN IF NOT EXISTS expired_email_signup BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE email_verification_tokens
    ADD COLUMN IF NOT EXISTS cleanup_eligible BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_email_signup_cleanup
    ON email_verification_tokens (cleanup_eligible, used_at, created_at, user_id);
