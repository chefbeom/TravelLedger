ALTER TABLE recurring_ledger_rules
    ADD COLUMN IF NOT EXISTS month_interval INT NULL;

UPDATE recurring_ledger_rules
SET month_interval = 1
WHERE (schedule_type = 'MONTHLY_DATE' OR schedule_type IS NULL)
  AND (month_interval IS NULL OR month_interval < 1);
