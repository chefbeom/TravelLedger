ALTER TABLE drive_items
    ADD COLUMN IF NOT EXISTS system_managed BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE drive_items
    ADD COLUMN IF NOT EXISTS source_type VARCHAR(40) NULL;

ALTER TABLE drive_items
    ADD COLUMN IF NOT EXISTS source_reference VARCHAR(160) NULL;

ALTER TABLE drive_items
    ADD INDEX IF NOT EXISTS idx_drive_items_owner_source (owner_id, source_type, source_reference);