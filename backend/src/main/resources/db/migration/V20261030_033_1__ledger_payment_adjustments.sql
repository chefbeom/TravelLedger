-- Existing transactions remain included; this version precedes pending account migrations.
ALTER TABLE ledger_entries ADD COLUMN IF NOT EXISTS financially_excluded BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE ledger_entries ADD COLUMN IF NOT EXISTS cancelled_at DATETIME(6) NULL;
ALTER TABLE ledger_entries ADD COLUMN IF NOT EXISTS refunded_principal DECIMAL(15,2) NOT NULL DEFAULT 0;
ALTER TABLE ledger_entries ADD COLUMN IF NOT EXISTS has_adjustment_history BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE ledger_entries ADD COLUMN IF NOT EXISTS adjustment_type VARCHAR(30) NULL;
ALTER TABLE ledger_entries ADD COLUMN IF NOT EXISTS adjustment_origin_id BIGINT NULL;
ALTER TABLE ledger_entries ADD COLUMN IF NOT EXISTS adjustment_request_key VARCHAR(36) NULL;
ALTER TABLE ledger_entries ADD COLUMN IF NOT EXISTS adjustment_principal DECIMAL(15,2) NULL;
ALTER TABLE ledger_entries ADD COLUMN IF NOT EXISTS adjustment_deduction DECIMAL(15,2) NULL;
ALTER TABLE ledger_entries ADD COLUMN IF NOT EXISTS adjustment_extra DECIMAL(15,2) NULL;
ALTER TABLE ledger_entries ADD COLUMN IF NOT EXISTS adjustment_voided_at DATETIME(6) NULL;
CREATE INDEX IF NOT EXISTS idx_ledger_adjustment_origin ON ledger_entries (owner_id, adjustment_origin_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ledger_adjustment_request ON ledger_entries (owner_id, adjustment_request_key);
