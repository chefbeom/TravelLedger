-- Existing direct-share logs start unprocessed; the bounded worker removes client identifiers.
ALTER TABLE drive_download_link_access_logs
    ADD COLUMN IF NOT EXISTS direct_share_anonymized BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_drive_direct_share_anonymization
    ON drive_download_link_access_logs (direct_share_anonymized, link_id, status, id);
