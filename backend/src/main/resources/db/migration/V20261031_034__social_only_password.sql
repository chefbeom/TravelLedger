-- Existing local passwords are unchanged. New Kakao-only accounts have no local password.
ALTER TABLE app_users MODIFY COLUMN password_hash VARCHAR(100) NULL;
