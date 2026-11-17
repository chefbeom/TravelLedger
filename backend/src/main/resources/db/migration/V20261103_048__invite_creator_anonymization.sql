-- Keep invite outcome/timestamps without retaining a departed creator's identity.
ALTER TABLE account_invites MODIFY COLUMN created_by_user_id BIGINT NULL;
ALTER TABLE account_invites ADD COLUMN IF NOT EXISTS revoked_at DATETIME(6);
