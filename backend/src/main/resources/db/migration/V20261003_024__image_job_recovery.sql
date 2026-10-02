ALTER TABLE ledger_image_analysis_requests ADD COLUMN IF NOT EXISTS effective_prompt LONGTEXT NULL;

-- Preserve every history row; only the newest row retains a duplicated client request ID.
UPDATE ledger_image_analysis_requests older
JOIN ledger_image_analysis_requests newer
  ON older.owner_id = newer.owner_id AND older.client_request_id = newer.client_request_id AND older.id < newer.id
SET older.client_request_id = NULL
WHERE older.client_request_id IS NOT NULL;

ALTER TABLE ledger_image_analysis_requests
    ADD UNIQUE INDEX IF NOT EXISTS uk_ledger_image_owner_client (owner_id, client_request_id);
