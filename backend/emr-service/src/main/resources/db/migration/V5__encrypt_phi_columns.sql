-- Encrypts PHI at rest for every clinical free-text/named field, via EncryptedStringConverter
-- (AES-256-GCM, see backend/common/src/main/java/com/healthplatform/common/crypto/). Every
-- touched column widens to TEXT: base64(IV + ciphertext + tag) is longer and more
-- variable-length than the plaintext it replaces, so a VARCHAR(n) cap risks silent truncation
-- regardless of n. `encounters.notes` is already TEXT — included here for symmetry/documentation.
--
-- Deliberately NOT touched: patient_id/doctor_id/encounter_id/appointment_id (real UUIDs, used
-- in WHERE clauses and foreign keys — must stay queryable), allergies.severity (a plain
-- MILD/MODERATE/SEVERE enum-like value, low sensitivity), clinical_documents.status/
-- type_system/type_code/type_display/content_type/object_key/size_bytes (codes and a pointer
-- into object storage, not PHI content themselves).
--
-- IMPORTANT: any row written before this migration becomes unreadable after it runs —
-- Hibernate will attempt AES-GCM decryption on old plaintext bytes and throw. This platform
-- has no production data; reset local dev state (docker compose down -v, or drop+recreate
-- emr_db) after this ships rather than attempting an in-place backfill.

ALTER TABLE encounters ALTER COLUMN notes TYPE TEXT;

ALTER TABLE diagnoses ALTER COLUMN icd10_code TYPE TEXT;
ALTER TABLE diagnoses ALTER COLUMN description TYPE TEXT;

ALTER TABLE medications ALTER COLUMN name TYPE TEXT;
ALTER TABLE medications ALTER COLUMN dosage TYPE TEXT;

ALTER TABLE lab_results ALTER COLUMN test_name TYPE TEXT;
ALTER TABLE lab_results ALTER COLUMN result_value TYPE TEXT;
ALTER TABLE lab_results ALTER COLUMN reference_range TYPE TEXT;

ALTER TABLE allergies ALTER COLUMN substance TYPE TEXT;

ALTER TABLE clinical_documents ALTER COLUMN title TYPE TEXT;
