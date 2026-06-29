ALTER TABLE ledger_entries
    ADD COLUMN IF NOT EXISTS foreign_currency_code VARCHAR(3) NULL;

ALTER TABLE ledger_entries
    ADD COLUMN IF NOT EXISTS foreign_amount DECIMAL(18, 4) NULL;

ALTER TABLE ledger_entries
    ADD COLUMN IF NOT EXISTS exchange_rate_to_krw DECIMAL(18, 6) NULL;

ALTER TABLE ledger_entries
    ADD COLUMN IF NOT EXISTS exchange_rate_date DATE NULL;

ALTER TABLE ledger_entries
    ADD COLUMN IF NOT EXISTS exchange_rate_provider VARCHAR(40) NULL;

ALTER TABLE ledger_entries
    ADD COLUMN IF NOT EXISTS travel_plan_id BIGINT NULL;

ALTER TABLE ledger_entries
    ADD COLUMN IF NOT EXISTS travel_record_id BIGINT NULL;

ALTER TABLE ledger_entries
    ADD INDEX IF NOT EXISTS idx_ledger_entries_owner_deleted_date_id (owner_id, deleted_at, entry_date, id);

ALTER TABLE ledger_entries
    ADD INDEX IF NOT EXISTS idx_ledger_entries_owner_deleted_type_date (owner_id, deleted_at, entry_type, entry_date);

ALTER TABLE ledger_entries
    ADD INDEX IF NOT EXISTS idx_ledger_entries_owner_title (owner_id, title);

ALTER TABLE ledger_entries
    ADD INDEX IF NOT EXISTS idx_ledger_entries_owner_memo (owner_id, memo);

ALTER TABLE ledger_entries
    ADD INDEX IF NOT EXISTS idx_ledger_entries_owner_category_date (owner_id, category_group_id, category_detail_id, entry_date);

ALTER TABLE ledger_entries
    ADD INDEX IF NOT EXISTS idx_ledger_entries_owner_payment_date (owner_id, payment_method_id, entry_date);

ALTER TABLE category_groups
    ADD INDEX IF NOT EXISTS idx_category_groups_owner_name (owner_id, name);

ALTER TABLE category_groups
    ADD INDEX IF NOT EXISTS idx_category_groups_owner_active_type (owner_id, active, entry_type);

ALTER TABLE category_details
    ADD INDEX IF NOT EXISTS idx_category_details_group_name (group_id, name);

ALTER TABLE category_details
    ADD INDEX IF NOT EXISTS idx_category_details_group_active (group_id, active);

ALTER TABLE payment_methods
    ADD INDEX IF NOT EXISTS idx_payment_methods_owner_name (owner_id, name);

ALTER TABLE payment_methods
    ADD INDEX IF NOT EXISTS idx_payment_methods_owner_active (owner_id, active);