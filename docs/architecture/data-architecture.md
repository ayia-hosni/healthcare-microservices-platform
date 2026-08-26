# Data Architecture

> Part of [Architecture](README.md). Deep dive: [06-data-architecture.md](06-data-architecture.md).

## Database-per-Service

Each service owns its persistence boundary — nine PostgreSQL databases, one per domain service
(`graphql-gateway` owns none; it is a stateless BFF):

```text
identity_db  •  patient_db  •  doctor_db  •  appointment_db  •  emr_db
billing_db  •  notification_db  •  audit_db  •  analytics_db
```

```text
                         ❌ Not Allowed

Service A ─────────────────────────────► Service B Database


                         ✅ Preferred

Service A ── REST / gRPC / Kafka Event ──► Service B
```

This keeps domain boundaries explicit and prevents services from becoming tightly coupled
through shared persistence. Cross-service information flows through APIs or domain events,
never shared tables.

---

## Concurrency & Idempotency

Appointment booking is designed to handle concurrent and duplicate requests.

```text
                         Client Request
                                │
                                ▼
                       Idempotency Key Check
                                │
                    ┌───────────┴───────────┐
                    │                       │
              Already Processed?       New Request
                    │                       │
                    ▼                       ▼
              Return Existing          Pessimistic Lock
                  Result               (conflict detection)
                                            │
                                            ▼
                                  Database Unique Constraint
                                    (final consistency boundary)
                                            │
                                            ▼
                                  Persist Appointment
```

Lower-contention entities use optimistic locking with `@Version` instead of pessimistic
locking. These mechanisms together protect against duplicate requests and concurrent
double-booking.

---

## Caching

Redis caches read-heavy lookups (patient/doctor) as a disposable, rebuild-from-Postgres
layer — not a system of record.

## Object storage

`emr-service` stores clinical document metadata in Postgres and the actual file bytes in
MinIO locally (a cloud object store — Azure Blob Storage or S3 — in a real deployment). See
[`../cloud/azure/data-services.md`](../cloud/azure/data-services.md) for the cloud-specific
version.

## PHI encryption

Patient names, DOB, contact info, and clinical notes/diagnoses are encrypted at rest
(AES-256-GCM, field-level, via a JPA `AttributeConverter` in `common/crypto`); login email
uses a separate deterministic HMAC blind-index column so it stays searchable-by-equality
without exposing plaintext. This was added after
[06-data-architecture.md](06-data-architecture.md) was written, so that page doesn't cover it
yet — see the `V*__encrypt_phi_columns.sql` / `V*__email_encryption_and_blind_index.sql`
migrations in each service's `db/migration/` for the schema-level detail.
