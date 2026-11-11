-- Photos attached to a place may not have a known visit date.
ALTER TABLE record_map_visits MODIFY COLUMN visit_date DATE NULL;
