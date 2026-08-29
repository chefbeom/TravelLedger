CREATE TABLE IF NOT EXISTS recurring_ledger_rules (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_id BIGINT NOT NULL,
    title VARCHAR(120) NOT NULL,
    memo VARCHAR(500) NULL,
    amount DECIMAL(15, 2) NOT NULL,
    entry_type VARCHAR(20) NOT NULL,
    schedule_type VARCHAR(30) NOT NULL DEFAULT 'MONTHLY_DATE',
    month_interval INT NULL,
    day_of_month INT NULL,
    interval_days INT NULL,
    start_date DATE NOT NULL,
    end_date DATE NULL,
    mode VARCHAR(20) NOT NULL,
    category_group_id BIGINT NOT NULL,
    category_detail_id BIGINT NULL,
    payment_method_id BIGINT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);

ALTER TABLE recurring_ledger_rules
    ADD INDEX IF NOT EXISTS idx_recurring_ledger_rules_owner_active (owner_id, active, day_of_month),
    ADD INDEX IF NOT EXISTS idx_recurring_ledger_rules_owner_start (owner_id, start_date, end_date);

CREATE TABLE IF NOT EXISTS recurring_ledger_occurrences (
    id BIGINT NOT NULL AUTO_INCREMENT,
    rule_id BIGINT NOT NULL,
    scheduled_date DATE NOT NULL,
    mode VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_entry_id BIGINT NULL,
    processed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_recurring_ledger_occurrences_rule_date (rule_id, scheduled_date)
);

ALTER TABLE recurring_ledger_occurrences
    ADD INDEX IF NOT EXISTS idx_recurring_ledger_occurrences_rule_status (rule_id, status, scheduled_date),
    ADD INDEX IF NOT EXISTS idx_recurring_ledger_occurrences_status_date (status, scheduled_date);
