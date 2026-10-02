CREATE TABLE IF NOT EXISTS travel_photo_cluster_refresh_jobs (
    owner_id BIGINT NOT NULL PRIMARY KEY,
    generation BIGINT NOT NULL,
    processed_generation BIGINT NOT NULL,
    dirty BOOLEAN NOT NULL,
    INDEX idx_cluster_refresh_dirty_owner (dirty, owner_id)
);
