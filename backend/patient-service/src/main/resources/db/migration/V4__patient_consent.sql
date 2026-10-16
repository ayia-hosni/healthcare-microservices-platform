-- Backs consent management (grant/revoke): tracked as a single flag per patient, not PHI
-- itself, so it stays a plain boolean/timestamp rather than going through
-- EncryptedStringConverter like the columns touched in V3.

ALTER TABLE patients ADD COLUMN consent_granted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE patients ADD COLUMN consent_updated_at TIMESTAMPTZ;
