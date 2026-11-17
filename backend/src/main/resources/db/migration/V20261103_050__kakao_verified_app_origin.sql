-- Existing rows intentionally remain unknown until a fresh, verified Kakao login.
ALTER TABLE social_accounts ADD COLUMN IF NOT EXISTS provider_app_id BIGINT NULL;
