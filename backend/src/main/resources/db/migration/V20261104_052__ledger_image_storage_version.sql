-- Only newly verified uploads can record storage identity and immutable object version.
ALTER TABLE ledger_image_analysis_requests ADD COLUMN IF NOT EXISTS image_storage_id VARCHAR(80) NULL;
ALTER TABLE ledger_image_analysis_requests ADD COLUMN IF NOT EXISTS image_object_version VARCHAR(1024) NULL;
