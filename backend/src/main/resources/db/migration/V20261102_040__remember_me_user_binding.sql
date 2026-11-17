CREATE TABLE IF NOT EXISTS persistent_logins (
    username VARCHAR(64) NOT NULL,
    series VARCHAR(64) NOT NULL,
    token VARCHAR(64) NOT NULL,
    last_used TIMESTAMP NOT NULL,
    user_id BIGINT NULL,
    PRIMARY KEY (series)
);

ALTER TABLE persistent_logins ADD COLUMN IF NOT EXISTS user_id BIGINT NULL;

-- Bind only the pre-existing names during the first completed migration. Replaying
-- this script after a login name has been reused must never adopt an orphan token.
CREATE TABLE IF NOT EXISTS remember_me_binding_migration (
    marker VARCHAR(40) NOT NULL PRIMARY KEY,
    applied_at TIMESTAMP NOT NULL
);

UPDATE persistent_logins
SET user_id = (SELECT app_users.id FROM app_users WHERE app_users.login_id = persistent_logins.username)
WHERE user_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM remember_me_binding_migration WHERE marker = 'V20261102_040');

INSERT INTO remember_me_binding_migration (marker, applied_at)
SELECT 'V20261102_040', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM remember_me_binding_migration WHERE marker = 'V20261102_040');

CREATE INDEX IF NOT EXISTS idx_persistent_logins_user_id ON persistent_logins (user_id);
