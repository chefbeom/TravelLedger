-- Historical rows stay NULL: their original bucket cannot be inferred safely.
ALTER TABLE ledger_image_analysis_requests ADD COLUMN IF NOT EXISTS image_storage_bucket VARCHAR(255) NULL;
