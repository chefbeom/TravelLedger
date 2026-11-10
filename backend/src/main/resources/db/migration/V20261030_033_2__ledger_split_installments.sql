-- A split payment is stored as linked ledger entries, one per payment method.
ALTER TABLE ledger_entries ADD COLUMN IF NOT EXISTS installment_months INT NULL;
ALTER TABLE ledger_entries ADD COLUMN IF NOT EXISTS split_payment_group_id VARCHAR(36) NULL;
ALTER TABLE ledger_entries ADD COLUMN IF NOT EXISTS split_payment_index INT NULL;
ALTER TABLE ledger_entries ADD COLUMN IF NOT EXISTS split_payment_count INT NULL;
ALTER TABLE ledger_entries ADD COLUMN IF NOT EXISTS split_payment_request_hash VARCHAR(64) NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_ledger_split_payment_part ON ledger_entries (owner_id, split_payment_group_id, split_payment_index);
