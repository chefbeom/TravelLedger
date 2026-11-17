CREATE TABLE IF NOT EXISTS calendar_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_id BIGINT NOT NULL,
    title VARCHAR(160) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    start_time TIME NULL,
    end_time TIME NULL,
    all_day BOOLEAN NOT NULL,
    memo VARCHAR(4000) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_calendar_events_owner FOREIGN KEY (owner_id) REFERENCES app_users(id),
    INDEX idx_calendar_events_owner_dates (owner_id, start_date, end_date)
);
