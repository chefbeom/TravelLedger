ALTER TABLE recurring_ledger_rules
    ADD COLUMN IF NOT EXISTS schedule_type VARCHAR(30) NOT NULL DEFAULT 'MONTHLY_DATE',
    ADD COLUMN IF NOT EXISTS month_interval INT NULL,
    ADD COLUMN IF NOT EXISTS interval_days INT NULL;

ALTER TABLE recurring_ledger_rules
    MODIFY COLUMN day_of_month INT NULL;

UPDATE recurring_ledger_rules
SET schedule_type = 'MONTHLY_DATE'
WHERE schedule_type IS NULL OR schedule_type = '';

UPDATE recurring_ledger_rules
SET month_interval = 1
WHERE (schedule_type = 'MONTHLY_DATE' OR schedule_type IS NULL) AND (month_interval IS NULL OR month_interval < 1);
