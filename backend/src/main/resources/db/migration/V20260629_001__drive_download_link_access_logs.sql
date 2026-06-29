CREATE TABLE IF NOT EXISTS drive_download_link_access_logs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    link_id BIGINT NULL,
    item_id BIGINT NULL,
    owner_id BIGINT NULL,
    status VARCHAR(32) NOT NULL,
    token_fingerprint VARCHAR(64) NOT NULL,
    client_address VARCHAR(64) NULL,
    user_agent VARCHAR(255) NULL,
    accessed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_drive_download_link_access_logs_link_owner_time
    ON drive_download_link_access_logs (link_id, owner_id, accessed_at);

CREATE INDEX idx_drive_download_link_access_logs_token_time
    ON drive_download_link_access_logs (token_fingerprint, accessed_at);