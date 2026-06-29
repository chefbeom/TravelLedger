CREATE TABLE IF NOT EXISTS ledger_classification_rules (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_id BIGINT NOT NULL,
    keyword VARCHAR(160) NOT NULL,
    normalized_keyword VARCHAR(160) NOT NULL,
    entry_type VARCHAR(20) NULL,
    category_group_id BIGINT NOT NULL,
    category_detail_id BIGINT NULL,
    payment_method_id BIGINT NULL,
    priority INT NOT NULL DEFAULT 100,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_ledger_classification_rules_owner_active_priority
    ON ledger_classification_rules (owner_id, active, priority, id);

CREATE INDEX idx_ledger_classification_rules_owner_keyword
    ON ledger_classification_rules (owner_id, normalized_keyword);