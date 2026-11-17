-- Short-lived keyed fingerprints, never raw IP addresses or email addresses.
CREATE TABLE IF NOT EXISTS withdrawal_recovery_request_limits (
    fingerprint VARCHAR(43) NOT NULL,
    request_count INT NOT NULL,
    window_started_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (fingerprint)
);
CREATE INDEX IF NOT EXISTS idx_withdrawal_recovery_limit_expiry ON withdrawal_recovery_request_limits (expires_at);
