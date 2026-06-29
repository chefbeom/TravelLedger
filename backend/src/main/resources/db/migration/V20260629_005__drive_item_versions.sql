-- Adds the first explicit CalenDrive file version ledger. The table stores
-- owner-scoped object metadata only; object bytes remain in the existing
-- storage provider and are referenced through storage_path.

CREATE TABLE IF NOT EXISTS drive_item_versions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    item_id BIGINT NOT NULL,
    owner_id BIGINT NOT NULL,
    version_number INT NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    extension VARCHAR(40) NOT NULL DEFAULT '',
    stored_name VARCHAR(255) NOT NULL,
    storage_path VARCHAR(600) NOT NULL,
    content_type VARCHAR(120) NOT NULL,
    file_size BIGINT NOT NULL,
    source VARCHAR(40) NOT NULL DEFAULT 'UPLOAD',
    created_at DATETIME NOT NULL,
    CONSTRAINT fk_drive_item_versions_item FOREIGN KEY (item_id) REFERENCES drive_items(id),
    CONSTRAINT fk_drive_item_versions_owner FOREIGN KEY (owner_id) REFERENCES app_users(id)
);

CREATE INDEX idx_drive_item_versions_item_owner ON drive_item_versions (item_id, owner_id, version_number DESC, id DESC);
CREATE INDEX idx_drive_item_versions_owner_created ON drive_item_versions (owner_id, created_at DESC);