CREATE TABLE IF NOT EXISTS app_work_leases (
    lease_key VARCHAR(240) NOT NULL PRIMARY KEY,
    owner_token VARCHAR(36) NOT NULL,
    expires_at_epoch_millis BIGINT NOT NULL,
    INDEX idx_work_leases_expiry (expires_at_epoch_millis)
);
