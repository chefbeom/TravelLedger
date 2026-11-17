-- A transferred plan may still reference objects under an earlier owner's key prefix.
ALTER TABLE travel_plans ADD COLUMN IF NOT EXISTS storage_owner_history_json TEXT NULL;
