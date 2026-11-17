-- Existing attachment locations remain unknown until separately reconciled.
ALTER TABLE support_inquiries ADD COLUMN IF NOT EXISTS attachment_storage_id VARCHAR(80);
ALTER TABLE support_inquiries ADD COLUMN IF NOT EXISTS attachment_sha256 VARCHAR(64);
