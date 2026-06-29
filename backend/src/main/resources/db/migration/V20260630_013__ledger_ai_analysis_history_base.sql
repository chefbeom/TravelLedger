CREATE TABLE IF NOT EXISTS ledger_ai_analysis_histories (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_id BIGINT NOT NULL,
    mode VARCHAR(20) NOT NULL,
    period_type VARCHAR(20) NOT NULL,
    comparison_preset VARCHAR(40) NULL,
    status VARCHAR(20) NOT NULL,
    from_date DATE NOT NULL,
    to_date DATE NOT NULL,
    compare_from_date DATE NULL,
    compare_to_date DATE NULL,
    model VARCHAR(80) NOT NULL,
    provider VARCHAR(40) NOT NULL DEFAULT 'unknown',
    title VARCHAR(160) NOT NULL,
    summary VARCHAR(2000) NULL,
    error_message VARCHAR(1000) NULL,
    request_payload_json LONGTEXT NULL,
    result_json LONGTEXT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);

ALTER TABLE ledger_ai_analysis_histories
    ADD COLUMN IF NOT EXISTS provider VARCHAR(40) NOT NULL DEFAULT 'unknown';

ALTER TABLE ledger_ai_analysis_histories
    ADD INDEX IF NOT EXISTS idx_ledger_ai_history_owner_created (owner_id, created_at, id);

ALTER TABLE ledger_ai_analysis_histories
    ADD INDEX IF NOT EXISTS idx_ledger_ai_history_owner_range (owner_id, from_date, to_date);

ALTER TABLE ledger_ai_analysis_histories
    ADD INDEX IF NOT EXISTS idx_ledger_ai_history_owner_mode (owner_id, mode, period_type);

ALTER TABLE ledger_ai_analysis_histories
    ADD INDEX IF NOT EXISTS idx_ledger_ai_history_owner_provider_model_range (owner_id, provider, model, mode, period_type, from_date, to_date, created_at);