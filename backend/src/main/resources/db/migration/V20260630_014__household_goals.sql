CREATE TABLE IF NOT EXISTS household_goals (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_id BIGINT NOT NULL,
    title VARCHAR(120) NOT NULL,
    target_amount_krw DECIMAL(19,2) NOT NULL,
    current_amount_krw DECIMAL(19,2) NOT NULL,
    due_date DATE NULL,
    status VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);

ALTER TABLE household_goals
    ADD INDEX IF NOT EXISTS idx_household_goals_owner_status (owner_id, status, created_at, id),
    ADD INDEX IF NOT EXISTS idx_household_goals_owner_due (owner_id, due_date);