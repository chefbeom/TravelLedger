ALTER TABLE travel_route_segments
    MODIFY COLUMN route_path_json LONGTEXT NOT NULL;

ALTER TABLE travel_route_segments
    ADD COLUMN IF NOT EXISTS line_color_hex VARCHAR(7) NULL;

ALTER TABLE travel_route_segments
    ADD COLUMN IF NOT EXISTS line_style VARCHAR(20) NULL;

ALTER TABLE travel_route_segments
    ADD COLUMN IF NOT EXISTS gpx_files_json LONGTEXT NULL;