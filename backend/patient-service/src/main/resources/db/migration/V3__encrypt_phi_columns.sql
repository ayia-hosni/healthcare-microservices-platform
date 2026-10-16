-- Encrypts PHI at rest: firstName, lastName, dateOfBirth, phoneNumber, email,
-- medicalHistorySummary, and the embedded insurance fields are now written/read through
-- EncryptedStringConverter/EncryptedLocalDateConverter (AES-256-GCM, see
-- backend/common/src/main/java/com/healthplatform/common/crypto/). Every touched column
-- widens to TEXT: base64(IV + ciphertext + tag) is longer and more variable-length than the
-- plaintext it replaces, so a VARCHAR(n) cap risks silent truncation regardless of n.
--
-- date_of_birth is a real semantic type change, not just a width change: DATE -> TEXT, since
-- the column now holds opaque ciphertext, not something Postgres can parse as a date. The
-- USING cast lets this ALTER succeed against any existing DATE rows at migration time.
--
-- The functional indexes on lower(first_name)/lower(last_name) are dropped: they were built
-- for the SQL LIKE search PatientRepository.searchByName used to run, which no longer exists
-- (ciphertext can't be pattern-matched) — see PatientService.search()'s in-app replacement.
--
-- IMPORTANT: any row written before this migration becomes unreadable after it runs —
-- Hibernate will attempt AES-GCM decryption on old plaintext bytes and throw. This platform
-- has no production data; reset local dev state (docker compose down -v, or drop+recreate
-- patient_db) after this ships rather than attempting an in-place backfill. See GITOPS.md/
-- PROGRESS.md conventions for how this repo documents dev-only breaking changes.

DROP INDEX IF EXISTS idx_patients_last_name;
DROP INDEX IF EXISTS idx_patients_first_name;

ALTER TABLE patients ALTER COLUMN first_name TYPE TEXT;
ALTER TABLE patients ALTER COLUMN last_name TYPE TEXT;
ALTER TABLE patients ALTER COLUMN date_of_birth TYPE TEXT USING date_of_birth::TEXT;
ALTER TABLE patients ALTER COLUMN phone_number TYPE TEXT;
ALTER TABLE patients ALTER COLUMN email TYPE TEXT;
ALTER TABLE patients ALTER COLUMN insurance_provider TYPE TEXT;
ALTER TABLE patients ALTER COLUMN insurance_policy_number TYPE TEXT;
ALTER TABLE patients ALTER COLUMN insurance_group_number TYPE TEXT;
