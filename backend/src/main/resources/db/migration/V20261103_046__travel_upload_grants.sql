-- Record the signed object locations and expiry, without retaining presigned URLs.
CREATE TABLE IF NOT EXISTS travel_upload_grants (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_id BIGINT NOT NULL,
    plan_id BIGINT NOT NULL,
    source_id BIGINT NOT NULL,
    kind VARCHAR(16) NOT NULL,
    object_key VARCHAR(600) NOT NULL,
    original_key VARCHAR(600),
    storage_bucket VARCHAR(255) NOT NULL,
    signed_at_utc DATETIME(6) NOT NULL,
    expires_at_utc DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_travel_upload_grants_owner ON travel_upload_grants (owner_id, id);
