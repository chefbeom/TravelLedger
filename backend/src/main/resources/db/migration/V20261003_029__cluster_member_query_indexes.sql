ALTER TABLE travel_photo_cluster_members ADD INDEX IF NOT EXISTS idx_cluster_members_owner_cluster_order (owner_id, cluster_id, sort_order);
ALTER TABLE travel_photo_cluster_members ADD INDEX IF NOT EXISTS idx_cluster_members_owner_media (owner_id, media_id);
