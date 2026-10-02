CREATE TABLE IF NOT EXISTS ledger_excel_analysis_jobs (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    input_json LONGTEXT NULL,
    result_json LONGTEXT NULL,
    error_message VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_excel_jobs_owner_status (owner_id, status),
    INDEX idx_excel_jobs_status_created (status, created_at)
);
