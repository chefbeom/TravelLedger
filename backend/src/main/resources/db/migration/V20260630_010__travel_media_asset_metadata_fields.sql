ALTER TABLE travel_media_assets
    ADD COLUMN IF NOT EXISTS gps_latitude DECIMAL(10,7) NULL;

ALTER TABLE travel_media_assets
    ADD COLUMN IF NOT EXISTS gps_longitude DECIMAL(10,7) NULL;

ALTER TABLE travel_media_assets
    ADD COLUMN IF NOT EXISTS representative_override BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE travel_media_assets
    ADD COLUMN IF NOT EXISTS gps_extracted_at DATETIME NULL;

ALTER TABLE travel_media_assets
    ADD INDEX IF NOT EXISTS idx_travel_media_assets_gps (gps_latitude, gps_longitude);

ALTER TABLE travel_media_assets
    ADD INDEX IF NOT EXISTS idx_travel_media_assets_rep_override (representative_override);