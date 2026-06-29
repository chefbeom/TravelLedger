CREATE INDEX idx_drive_download_link_access_logs_item_owner_direct
    ON drive_download_link_access_logs (item_id, owner_id, link_id, accessed_at);