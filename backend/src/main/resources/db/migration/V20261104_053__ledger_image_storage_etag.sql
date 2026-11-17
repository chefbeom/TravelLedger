-- Optional provider comparison hint for future reconciliation; old objects remain unknown.
ALTER TABLE ledger_image_analysis_requests ADD COLUMN IF NOT EXISTS image_object_etag VARCHAR(128) NULL;
