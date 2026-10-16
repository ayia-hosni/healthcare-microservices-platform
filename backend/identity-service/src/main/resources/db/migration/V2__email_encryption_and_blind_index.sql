-- Encrypts the login email at rest (AES-256-GCM, EncryptedStringConverter — see
-- backend/common/src/main/java/com/healthplatform/common/crypto/) and adds a separate
-- deterministic blind-index column so login/registration can still look a user up by email.
--
-- Why a blind index is needed at all: AES-GCM here is intentionally nondeterministic (a fresh
-- random IV every encrypt), so the same email encrypted twice produces different ciphertext —
-- correct for confidentiality, but it means `email` can no longer be queried by equality or
-- carry a meaningful UNIQUE constraint. email_hash is HMAC-SHA256(lowercase(email)) computed
-- by AuthService via BlindIndexHasher (keyed separately from the AES key) — same input always
-- produces the same output, which is what makes IT indexable/unique. UserRepository now
-- queries findByEmailHash/existsByEmailHash instead of findByEmail/existsByEmail.
--
-- IMPORTANT: any row written before this migration becomes unreadable after it runs — the
-- `email` column holds plaintext that Hibernate will now try to AES-GCM-decrypt, and no
-- existing row has an email_hash value at all (impossible to backfill via SQL alone; it
-- requires the app-level HMAC key). This platform has no production data; reset local dev
-- state (docker compose down -v, or drop+recreate identity_db) after this ships.

ALTER TABLE users DROP CONSTRAINT IF EXISTS users_email_key;
ALTER TABLE users ALTER COLUMN email TYPE TEXT;

ALTER TABLE users ADD COLUMN email_hash VARCHAR(64);
-- No backfill (see note above) — this column is only usable against rows written after this
-- migration, i.e. after a dev-data reset. NOT NULL is added directly rather than in two steps
-- since there are no existing rows expected to violate it in practice.
ALTER TABLE users ALTER COLUMN email_hash SET NOT NULL;
CREATE UNIQUE INDEX idx_users_email_hash ON users (email_hash);
