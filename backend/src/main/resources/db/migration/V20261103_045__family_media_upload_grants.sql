-- Track upload location and signed expiry without retaining presigned URLs or signatures.
CREATE TABLE IF NOT EXISTS family_media_upload_grants (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_id BIGINT NOT NULL,
    category_id BIGINT NOT NULL,
    object_key VARCHAR(600) NOT NULL,
    storage_bucket VARCHAR(255) NOT NULL,
    signed_at_utc DATETIME(6) NOT NULL,
    expires_at_utc DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_family_upload_grants_owner ON family_media_upload_grants (owner_id, id);
