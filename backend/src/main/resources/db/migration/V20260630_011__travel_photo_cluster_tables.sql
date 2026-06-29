CREATE TABLE IF NOT EXISTS travel_photo_clusters (
    id BIGINT NOT NULL,
    owner_id BIGINT NOT NULL,
    representative_media_id BIGINT NOT NULL,
    representative_record_id BIGINT NOT NULL,
    plan_id BIGINT NOT NULL,
    plan_name VARCHAR(120) NOT NULL,
    plan_color_hex VARCHAR(7) NOT NULL,
    memory_date DATE NULL,
    memory_time TIME NULL,
    category VARCHAR(80) NULL,
    title VARCHAR(200) NULL,
    country VARCHAR(120) NULL,
    region VARCHAR(120) NULL,
    place_name VARCHAR(200) NULL,
    latitude DECIMAL(10,7) NOT NULL,
    longitude DECIMAL(10,7) NOT NULL,
    photo_count INT NOT NULL,
    memory_count INT NOT NULL,
    max_distance_meters DECIMAL(10,2) NOT NULL,
    representative_override BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id)
);

ALTER TABLE travel_photo_clusters
    ADD INDEX IF NOT EXISTS idx_travel_photo_clusters_owner (owner_id);

ALTER TABLE travel_photo_clusters
    ADD INDEX IF NOT EXISTS idx_travel_photo_clusters_rep_media (representative_media_id);

CREATE TABLE IF NOT EXISTS travel_photo_cluster_members (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_id BIGINT NOT NULL,
    cluster_id BIGINT NOT NULL,
    media_id BIGINT NOT NULL,
    sort_order INT NOT NULL,
    PRIMARY KEY (id)
);

ALTER TABLE travel_photo_cluster_members
    ADD INDEX IF NOT EXISTS idx_travel_photo_cluster_members_owner (owner_id);

ALTER TABLE travel_photo_cluster_members
    ADD INDEX IF NOT EXISTS idx_travel_photo_cluster_members_cluster (cluster_id);

ALTER TABLE travel_photo_cluster_members
    ADD UNIQUE INDEX IF NOT EXISTS uq_travel_photo_cluster_members_cluster_media (cluster_id, media_id);