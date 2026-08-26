# 🩺 patient-service

> **Patient demographics, insurance, and medical history for the Healthcare Platform.**

**Port:** `8082`
**Database:** `patient_db`
**Database role:** `patient_user`
**Domain events:** publishes `patient.events`

Owns everything about a patient that isn't clinical record-keeping (that's `emr-service`) or
identity/credentials (that's `identity-service`). A patient's `id` here is the *same* UUID as
their `identity-service` `User.id` — this service never issues its own identifiers, it enriches
an existing identity with demographic data.

---

## ✨ Capabilities

| Capability               | Description                                                                  |
| --------------------------- | ------------------------------------------------------------------------- |
| 📝 Registration            | Creates a patient record against an existing identity UUID                  |
| 🔍 Role-scoped reads        | Lookup, search, and update each have distinct `@PreAuthorize` role sets     |
| ⚡ Cached reads              | `getById` is Redis-backed; a phone update evicts the cache entry            |
| 🔁 Optimistic locking       | `@Version` prevents concurrent profile edits from silently clobbering each other |
| 🔌 Two gRPC surfaces        | A narrow exists-check for appointment-service, a full record for graphql-gateway |
| 📤 Outbox-backed events     | `PatientRegisteredEvent` commits atomically with the patient row            |

---

## 🎯 What It Does

```http
POST   /api/v1/patients                    hasAnyRole('ADMIN','PATIENT')
GET    /api/v1/patients/{id}               hasAnyRole('ADMIN','DOCTOR','NURSE','PATIENT')
GET    /api/v1/patients/search?query=...   hasAnyRole('ADMIN','DOCTOR','NURSE')
PATCH  /api/v1/patients/{id}/phone         hasAnyRole('ADMIN','PATIENT')
```

Every endpoint has its own role set — a patient can register and read/update their own record,
but only clinical staff can search across patients.

### Search

```sql
where lower(p.lastName)  like lower('%' || :query || '%')
   or lower(p.firstName) like lower('%' || :query || '%')
```

A case-insensitive substring match against first or last name — no pagination, no fuzzy
matching.

---

# 🏗️ Architecture

Three inbound paths converge on the same `PatientRepository`: REST, and two separate gRPC
contracts with different response shapes.

```text
══════════════════════════════════════════════════════════════════════════════════════════════
                                    PATIENT SERVICE
══════════════════════════════════════════════════════════════════════════════════════════════


    ┌────────────────────────────────────────────────────────────────────────────────────┐
    │                          1. REST — CRUD + SEARCH                                   │
    └────────────────────────────────────────────────────────────────────────────────────┘

        Client
          │
          ▼
    ┌─────────────────────────────────────┐
    │           PatientController          │
    └──────────────────┬──────────────────┘
                       │
                       ▼
    ┌──────────────────────────────────────────────────────────────┐
    │                       PatientService                          │
    │                                                                │
    │  register(req)                                                │
    │    new Patient(req.id(), ...)  ── id is explicit, NOT          │
    │                                    @GeneratedValue — it's the  │
    │                                    identity-service User.id    │
    │    save() + publish PatientRegisteredEvent (same transaction)  │
    │                                                                │
    │  getById(id)          @Cacheable("patients", key=#id)          │
    │  search(query)        no caching                                │
    │  updatePhoneNumber()  @CacheEvict("patients", key=#id)          │
    └──────────────────┬─────────────────────────────────────────────┘
                       │
              ┌─────────┴─────────┐
              ▼                   ▼
    ┌───────────────┐    ┌──────────────────┐
    │     Redis     │    │ PatientRepository│
    │ (getById only)│    │   (JPA)          │
    └───────────────┘    └────────┬─────────┘
                                  ▼
                          patient_db.patients
                          @Version — optimistic lock


══════════════════════════════════════════════════════════════════════════════════════════════


    ┌────────────────────────────────────────────────────────────────────────────────────┐
    │              2. gRPC — TWO CONTRACTS, TWO CALLERS, ONE REPOSITORY                  │
    └────────────────────────────────────────────────────────────────────────────────────┘


    appointment-service                              graphql-gateway
          │                                                 │
          │ gRPC PatientLookup.getPatient(patientId)        │ gRPC PatientDirectory.getPatient(patientId)
          ▼                                                 ▼
    ┌───────────────────────────┐                ┌──────────────────────────────┐
    │  PatientLookupGrpcService  │                │  PatientDirectoryGrpcService  │
    │                            │                │                                │
    │  response: exists,         │                │  response: exists, id,        │
    │            firstName,      │                │    firstName, lastName,       │
    │            lastName        │                │    dateOfBirth, email,        │
    │  (existence check only —   │                │    phoneNumber                │
    │   minimal payload, no      │                │  (full record — added per     │
    │   PII beyond a name)       │                │   ADR-0004 for graphql-gateway│
    │                            │                │   to resolve `patient` fields │
    │                            │                │   without a REST round trip)  │
    └──────────────┬─────────────┘                └───────────────┬────────────────┘
                   │                                               │
                   └───────────────────┬───────────────────────────┘
                                       ▼
                           PatientRepository.findById(id)
                                       │
                          not found ──►  {exists: false}, both contracts
                          malformed UUID ──► {exists: false}, both contracts


══════════════════════════════════════════════════════════════════════════════════════════════


    ┌────────────────────────────────────────────────────────────────────────────────────┐
    │                   3. OUTBOX — PatientRegisteredEvent                              │
    └────────────────────────────────────────────────────────────────────────────────────┘


    PatientService.register(...)
          │
          │  (same @Transactional method, same DB transaction)
          ▼
    ┌────────────────────────────────────┐        ┌────────────────────────────────────┐
    │  patientRepository.save(patient)   │        │  PatientEventPublisher              │
    │  ──► patient_db.patients            │        │  .publishPatientRegistered(...)     │
    └────────────────────────────────────┘        └──────────────────┬───────────────────┘
                                                                      ▼
                                                    OutboxWriter.enqueue(
                                                      Topics.PATIENT_EVENTS,
                                                      key = patientId,
                                                      DomainEvent.of(..., "PatientRegisteredEvent", ...))
                                                                      │
                                                                      ▼
                                                          patient_db.outbox_events
                                                                      │
                                                                      ▼
                                                     OutboxRelay (common, polls the table)
                                                                      │
                                                                      ▼
                                                          Kafka: patient.events
```

---

# 📦 Package Structure

```text
patient-service/
└── src/main/java/.../patient/
    │
    ├── web/
    │   ├── PatientController
    │   └── dto/
    │       ├── PatientRequest
    │       └── PatientResponse
    │
    ├── service/
    │   └── PatientService
    │
    ├── grpc/
    │   ├── PatientLookupGrpcService
    │   └── PatientDirectoryGrpcService
    │
    ├── event/
    │   └── PatientEventPublisher
    │
    ├── repository/
    │   └── PatientRepository
    │
    └── domain/
        ├── Patient
        └── InsuranceInfo   (@Embeddable — provider, policyNumber, groupNumber)
```

---

# 🔗 Shared Identity, Not a Foreign Key

`Patient.id` has no `@GeneratedValue` — it's assigned explicitly from `PatientRequest.id` on
registration, and that value is expected to already be an `identity-service` `User.id`. There's
no foreign-key constraint enforcing that (it's a different database, in a different service),
so the relationship is a convention the registration flow depends on, not something the schema
guarantees. This is the same pattern behind why `PatientLookupGrpcService` and
`PatientDirectoryGrpcService` both return `{exists: false}` for a syntactically invalid UUID
before ever touching the database — a malformed id can't belong to any real patient, so it's
rejected before a query is even attempted.

---

# 🔌 Why Two gRPC Services, Not One

`PatientLookupGrpcService` and `PatientDirectoryGrpcService` both read from the same
`PatientRepository`, both handle a missing/malformed id the same way, and both exist to serve
one specific caller each:

| | Caller | Purpose | Response shape |
| --- | --- | --- | --- |
| **PatientLookup** | `appointment-service` | "does this patient exist?" before persisting a booking | `exists`, `firstName`, `lastName` only |
| **PatientDirectory** | `graphql-gateway` | resolve the full `patient` GraphQL field | `exists`, `id`, `firstName`, `lastName`, `dateOfBirth`, `email`, `phoneNumber` |

A single general-purpose service serving both would either over-fetch for
appointment-service's fast existence check or force `graphql-gateway` into a second round trip
for the fields `PatientLookup` doesn't carry. Two narrow, purpose-built contracts avoid both —
see [ADR-0004](../../docs/adr/0004-grpc-for-internal-synchronous-calls.md).

---

# 🚀 Where This Is Headed

`emr-service` still reaches this service over REST, not gRPC, for its referral workflow — that
read needs per-caller role-based access the gRPC surfaces don't support yet (both are
unauthenticated, trusted only within the cluster network). As real per-caller authorization
lands on the gRPC directory contract, that REST path is the one positioned to move onto it.

---

See [`../../docs/architecture/README.md`](../../docs/architecture/README.md) for the platform-wide communication model,
and [`../../docs/architecture/supporting-services.md`](../../docs/architecture/supporting-services.md) for how this service's
outbox-published events are consumed downstream.
