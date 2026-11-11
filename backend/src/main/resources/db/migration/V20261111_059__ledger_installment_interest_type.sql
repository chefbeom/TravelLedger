-- Existing installments have an unknown interest type; do not infer one.
ALTER TABLE ledger_entries ADD COLUMN IF NOT EXISTS installment_interest_type VARCHAR(16) NULL;
