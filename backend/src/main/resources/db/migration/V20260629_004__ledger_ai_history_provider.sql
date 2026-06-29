ALTER TABLE ledger_ai_analysis_histories
    ADD COLUMN provider VARCHAR(40) NOT NULL DEFAULT 'unknown';

CREATE INDEX idx_ledger_ai_history_owner_provider_model_range
    ON ledger_ai_analysis_histories (owner_id, provider, model, mode, period_type, from_date, to_date, created_at);
