-- Entity-declared indexes for ddl-auto environments and Flyway baselines that skipped inline table indexes.
CREATE INDEX IF NOT EXISTS idx_record_map_collection_owner ON record_map_collections (owner_id, created_at, id);
CREATE INDEX IF NOT EXISTS idx_record_map_place_owner_updated ON record_map_places (owner_id, updated_at, id);
CREATE INDEX IF NOT EXISTS idx_record_map_visit_owner_place_date ON record_map_visits (owner_id, place_id, visit_date, id);
CREATE INDEX IF NOT EXISTS idx_record_map_visit_owner_date ON record_map_visits (owner_id, visit_date, visit_time, id);
CREATE INDEX IF NOT EXISTS idx_record_map_photo_owner_visit ON record_map_photos (owner_id, visit_id, id);
CREATE INDEX IF NOT EXISTS idx_record_map_share_owner_collection ON record_map_shares (owner_id, collection_id);
CREATE INDEX IF NOT EXISTS idx_ledger_adjustment_origin ON ledger_entries (owner_id, adjustment_origin_id);
CREATE INDEX IF NOT EXISTS idx_ledger_entries_owner_travel_record ON ledger_entries (owner_id, travel_record_id);
