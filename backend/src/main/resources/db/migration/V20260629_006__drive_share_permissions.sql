ALTER TABLE drive_shares
    ADD COLUMN permission VARCHAR(16) NOT NULL DEFAULT 'DOWNLOAD';

UPDATE drive_shares
SET permission = 'DOWNLOAD'
WHERE permission IS NULL OR permission = '';