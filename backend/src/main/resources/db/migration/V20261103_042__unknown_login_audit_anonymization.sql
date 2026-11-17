-- Old and late legacy-node rows start unclassified and are processed in bounded batches.
ALTER TABLE login_audit_logs
    ADD COLUMN IF NOT EXISTS unknown_failure_privacy_processed BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_login_unknown_privacy
    ON login_audit_logs (unknown_failure_privacy_processed, app_user_id, status, id);
