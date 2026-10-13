-- Standalone map records. Existing travel tables and records are not rewritten.
CREATE TABLE IF NOT EXISTS record_map_collections (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    memo VARCHAR(1000) NULL,
    color_hex VARCHAR(7) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_record_map_collection_owner FOREIGN KEY (owner_id) REFERENCES app_users(id),
    INDEX idx_record_map_collection_owner (owner_id, created_at, id)
);
CREATE TABLE IF NOT EXISTS record_map_places (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    name VARCHAR(160) NOT NULL,
    memo VARCHAR(2000) NULL,
    country VARCHAR(80) NULL,
    region VARCHAR(120) NULL,
    latitude DOUBLE NOT NULL,
    longitude DOUBLE NOT NULL,
    manually_visited BOOLEAN NOT NULL DEFAULT FALSE,
    source_travel_record_id BIGINT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_record_map_place_owner FOREIGN KEY (owner_id) REFERENCES app_users(id),
    CONSTRAINT uk_record_map_travel_source UNIQUE (owner_id, source_travel_record_id),
    INDEX idx_record_map_place_owner_updated (owner_id, updated_at, id)
);
CREATE TABLE IF NOT EXISTS record_map_collection_places (
    place_id BIGINT NOT NULL,
    collection_id BIGINT NOT NULL,
    PRIMARY KEY (place_id, collection_id),
    CONSTRAINT fk_record_map_member_place FOREIGN KEY (place_id) REFERENCES record_map_places(id),
    CONSTRAINT fk_record_map_member_collection FOREIGN KEY (collection_id) REFERENCES record_map_collections(id),
    INDEX idx_record_map_member_collection (collection_id, place_id)
);
CREATE TABLE IF NOT EXISTS record_map_visits (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    place_id BIGINT NOT NULL,
    title VARCHAR(160) NOT NULL,
    memo VARCHAR(4000) NULL,
    visit_date DATE NOT NULL,
    visit_time TIME NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_record_map_visit_owner FOREIGN KEY (owner_id) REFERENCES app_users(id),
    CONSTRAINT fk_record_map_visit_place FOREIGN KEY (place_id) REFERENCES record_map_places(id),
    INDEX idx_record_map_visit_owner_place_date (owner_id, place_id, visit_date, id)
);
CREATE TABLE IF NOT EXISTS record_map_photos (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    visit_id BIGINT NOT NULL,
    original_name VARCHAR(220) NOT NULL,
    storage_path VARCHAR(600) NOT NULL,
    content_type VARCHAR(120) NOT NULL,
    file_size BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_record_map_photo_owner FOREIGN KEY (owner_id) REFERENCES app_users(id),
    CONSTRAINT fk_record_map_photo_visit FOREIGN KEY (visit_id) REFERENCES record_map_visits(id),
    INDEX idx_record_map_photo_owner_visit (owner_id, visit_id, id)
);
CREATE TABLE IF NOT EXISTS record_map_shares (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    collection_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    include_visits BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_record_map_share_owner FOREIGN KEY (owner_id) REFERENCES app_users(id),
    CONSTRAINT fk_record_map_share_collection FOREIGN KEY (collection_id) REFERENCES record_map_collections(id),
    INDEX idx_record_map_share_owner_collection (owner_id, collection_id)
);
