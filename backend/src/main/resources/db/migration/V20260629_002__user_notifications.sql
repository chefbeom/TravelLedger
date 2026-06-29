CREATE TABLE IF NOT EXISTS user_notifications (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_id BIGINT NOT NULL,
    type VARCHAR(60) NOT NULL,
    title VARCHAR(160) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    target_url VARCHAR(500) NULL,
    metadata_json TEXT NULL,
    read_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_user_notifications_owner_created
    ON user_notifications (owner_id, created_at, id);

CREATE INDEX idx_user_notifications_owner_read_created
    ON user_notifications (owner_id, read_at, created_at, id);