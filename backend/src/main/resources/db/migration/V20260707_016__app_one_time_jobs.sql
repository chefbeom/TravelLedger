CREATE TABLE IF NOT EXISTS app_one_time_jobs (
    job_key VARCHAR(160) PRIMARY KEY,
    completed_at TIMESTAMP NOT NULL,
    note VARCHAR(500) NULL
);