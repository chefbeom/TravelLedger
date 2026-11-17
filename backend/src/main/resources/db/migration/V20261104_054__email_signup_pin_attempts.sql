-- Account-scoped signup verification attempts survive link reissue and multiple app nodes.
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS signup_pin_failures INT NOT NULL DEFAULT 0;
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS signup_pin_window_started_at DATETIME NULL;
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS signup_pin_blocked_until DATETIME NULL;
