-- Preserve existing dates and allow an explicitly periodless plan.
ALTER TABLE travel_plans MODIFY COLUMN start_date DATE NULL;
ALTER TABLE travel_plans MODIFY COLUMN end_date DATE NULL;
