CREATE TABLE IF NOT EXISTS calendar_map_grants (
    id BIGINT NOT NULL AUTO_INCREMENT,
    viewer_id BIGINT NOT NULL,
    share_id BIGINT NOT NULL,
    share_owner_id BIGINT NOT NULL,
    selected BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id),
    CONSTRAINT fk_calendar_map_grant_viewer FOREIGN KEY (viewer_id) REFERENCES app_users(id),
    CONSTRAINT uk_calendar_map_grant_viewer_share UNIQUE (viewer_id, share_id),
    INDEX idx_calendar_map_grant_owner (share_owner_id)
);
