ALTER TABLE ledger_entry_change_histories
    MODIFY COLUMN summary VARCHAR(500) NOT NULL;

ALTER TABLE ledger_entry_change_histories
    MODIFY COLUMN before_snapshot_json LONGTEXT NOT NULL;

ALTER TABLE ledger_entry_change_histories
    MODIFY COLUMN after_snapshot_json LONGTEXT NOT NULL;

ALTER TABLE ledger_entry_change_histories
    ADD COLUMN IF NOT EXISTS changes_json LONGTEXT NULL;

ALTER TABLE ledger_entry_change_histories
    MODIFY COLUMN changes_json LONGTEXT NULL;