-- Recovery PIN limits follow a challenge across email-link reissues.
ALTER TABLE withdrawal_email_challenges ADD COLUMN IF NOT EXISTS pin_failures INT NOT NULL DEFAULT 0;
ALTER TABLE withdrawal_email_challenges ADD COLUMN IF NOT EXISTS pin_window_started_at DATETIME NULL;
ALTER TABLE withdrawal_email_challenges ADD COLUMN IF NOT EXISTS pin_blocked_until DATETIME NULL;
