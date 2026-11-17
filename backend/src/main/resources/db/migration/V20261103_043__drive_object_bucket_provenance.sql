-- No historical bucket is inferred: existing records stay NULL until independently verified.
ALTER TABLE drive_items ADD COLUMN IF NOT EXISTS storage_bucket VARCHAR(255) NULL;
ALTER TABLE drive_item_versions ADD COLUMN IF NOT EXISTS storage_bucket VARCHAR(255) NULL;
ALTER TABLE drive_profile_settings ADD COLUMN IF NOT EXISTS profile_image_bucket VARCHAR(255) NULL;
