ALTER TABLE travel_plans
    ADD INDEX IF NOT EXISTS idx_travel_plans_owner_start_id (owner_id, start_date, id),
    ADD INDEX IF NOT EXISTS idx_travel_plans_public_status_shared (public_shared, status, public_shared_at);

ALTER TABLE travel_budget_items
    ADD INDEX IF NOT EXISTS idx_travel_budget_items_plan_order (plan_id, display_order, id);

ALTER TABLE travel_expense_records
    ADD INDEX IF NOT EXISTS idx_travel_expense_plan_type_date (plan_id, record_type, expense_date, id),
    ADD INDEX IF NOT EXISTS idx_travel_expense_plan_coordinates (plan_id, latitude, longitude);

ALTER TABLE travel_media_assets
    ADD INDEX IF NOT EXISTS idx_travel_media_plan_type_uploaded (plan_id, media_type, uploaded_at, id),
    ADD INDEX IF NOT EXISTS idx_travel_media_record_uploaded (record_id, uploaded_at, id);

ALTER TABLE travel_route_segments
    ADD INDEX IF NOT EXISTS idx_travel_routes_plan_date (plan_id, route_date, id);
