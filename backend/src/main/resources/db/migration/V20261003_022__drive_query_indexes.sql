ALTER TABLE drive_items
    ADD INDEX IF NOT EXISTS idx_drive_owner_parent (owner_id, parent_id),
    ADD INDEX IF NOT EXISTS idx_drive_owner_modified (owner_id, last_modified_at, id),
    ADD INDEX IF NOT EXISTS idx_drive_owner_type_trash_modified (owner_id, item_type, trashed, last_modified_at, id);
