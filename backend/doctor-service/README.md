# 🩺 doctor-service

> **Doctor profiles, departments, specialties, and availability for the Healthcare Platform.**

**Port:** `8083`
**Database:** `doctor_db`
**Database role:** `doctor_user`
**Domain events:** publishes `doctor.events`

The architectural mirror of `patient-service` — same shared-identity convention, same
two-gRPC-contract split, same outbox-backed event publishing — plus a department association
and a weekly availability model patient-service has no equivalent of.

---

## ✨ Capabilities

| Capability                | Description                                                                    |
| ---------------------------| ------------------------------------------------------------------------- |
| 📝 Get-or-create departments | Creating a doctor auto-creates its department by name if one doesn't exist |
| ⚡ Cached specialty search   | `findBySpecialty` is Redis-backed — the hot path for patients browsing by specialty |
| 🔁 Optimistic locking        | `@Version` on `Doctor`, same pattern as `Patient`                         |
| 🗓️ Weekly availability model | `AvailabilitySlot` — recurring day-of-week + time-range windows, cascade-owned by `Doctor` |
| 🔌 Two gRPC surfaces         | A narrow exists-check for appointment-service, a full record for graphql-gateway |
| 📤 Outbox-backed events      | `DoctorCreatedEvent` commits atomically with the doctor row                |

---

## 🎯 What It Does

```http
POST   /api/v1/doctors                    hasRole('ADMIN')
GET    /api/v1/doctors
GET    /api/v1/doctors/{id}
GET    /api/v1/doctors/search?specialty=...
```

Only `create` is role-restricted — the three read endpoints have no `@PreAuthorize` at all,
unlike every read endpoint on `patient-service`.

### Department: get-or-create, not a separate admin flow

```java
departmentRepository.findByNameIgnoreCase(request.departmentName())
        .orElseGet(() -> departmentRepository.save(new Department(request.departmentName())));
```

There's no `POST /departments` endpoint. A department is created implicitly the first time a
doctor references a name that doesn't exist yet, and reused by every doctor after that — the
uniqueness constraint on `Department.name` is what prevents two rows for the same name under
concurrent creates.

---

# 🏗️ Architecture

```text
══════════════════════════════════════════════════════════════════════════════════════════════
                                     DOCTOR SERVICE
══════════════════════════════════════════════════════════════════════════════════════════════


    ┌────────────────────────────────────────────────────────────────────────────────────┐
    │                     1. REST — CREATE (WITH DEPARTMENT GET-OR-CREATE)               │
    └────────────────────────────────────────────────────────────────────────────────────┘

        Client
          │
          │ POST /api/v1/doctors   @hasRole('ADMIN')
          ▼
    ┌─────────────────────────────────────┐
    │            DoctorController          │
    └──────────────────┬──────────────────┘
                       │
                       ▼
    ┌──────────────────────────────────────────────────────────────┐
    │                        DoctorService.create(req)               │
    │                                                                │
    │  1. DepartmentRepository.findByNameIgnoreCase(name)            │
    │       found?  ──► reuse                                         │
    │       not found? ──► save(new Department(name))                 │
    │                                                                │
    │  2. new Doctor(req.id(), ..., department)                      │
    │       id is explicit, NOT @GeneratedValue — the identity-       │
    │       service User.id, same convention as Patient               │
    │                                                                │
    │  3. save() + publish DoctorCreatedEvent (same transaction)      │
    └──────────────────┬─────────────────────────────────────────────┘
                       │
              ┌─────────┴─────────┐
              ▼                   ▼
    ┌───────────────────┐   ┌──────────────────────┐
    │ DepartmentRepository│  │  DoctorRepository     │
    └───────────────────┘   └──────────┬───────────┘
                                       ▼
                              doctor_db.doctors / departments
                              @Version — optimistic lock


══════════════════════════════════════════════════════════════════════════════════════════════


    ┌────────────────────────────────────────────────────────────────────────────────────┐
    │                         2. REST — READ ENDPOINTS (NO AUTH)                        │
    └────────────────────────────────────────────────────────────────────────────────────┘


    GET /                        ──► DoctorService.findAll()
    GET /{id}                    ──► DoctorService.getById(id)
    GET /search?specialty=...    ──► DoctorService.findBySpecialty(specialty)
                                        │
                                        ▼
                              @Cacheable("doctor-availability", key=#specialty)
                                        │
                              ┌──────────┴──────────┐
                              ▼                     ▼
                            Redis            DoctorRepository
                       (specialty search       .findBySpecialtyIgnoreCase(...)
                          only)                        │
                                                        ▼
                                              doctor_db.doctors


══════════════════════════════════════════════════════════════════════════════════════════════


    ┌────────────────────────────────────────────────────────────────────────────────────┐
    │              3. gRPC — TWO CONTRACTS, ONE ASYMMETRY WORTH KNOWING                  │
    └────────────────────────────────────────────────────────────────────────────────────┘


    appointment-service                              graphql-gateway
          │                                                 │
          │ gRPC DoctorLookup.getDoctor(doctorId)           │ gRPC DoctorDirectory.getDoctor(doctorId)
          ▼                                                 ▼
    ┌───────────────────────────┐                ┌──────────────────────────────┐
    │  DoctorLookupGrpcService   │                │  DoctorDirectoryGrpcService   │
    │                            │                │                                │
    │  NOT @Transactional        │                │  @Transactional(readOnly=true)│
    │                            │                │  ── REQUIRED here, unlike      │
    │  response: exists,         │                │      Lookup: department is a   │
    │            firstName,      │                │      LAZY association, and    │
    │            lastName,       │                │      this service runs with   │
    │            specialty       │                │      open-in-view disabled —   │
    │  (no department — doesn't  │                │      without the transaction, │
    │   touch the lazy relation) │                │      accessing doctor.        │
    │                            │                │      getDepartment() outside   │
    │                            │                │      the session throws        │
    │                            │                │                                │
    │                            │                │  response: exists, id,         │
    │                            │                │    firstName, lastName,        │
    │                            │                │    specialty, departmentName   │
    │                            │                │    (departmentName omitted     │
    │                            │                │     entirely if null, not      │
    │                            │                │     sent as empty string)      │
    └──────────────┬─────────────┘                └───────────────┬────────────────┘
                   │                                               │
                   └───────────────────┬───────────────────────────┘
                                       ▼
                            DoctorRepository.findById(id)
                                       │
                          not found / malformed UUID ──► {exists: false}, both contracts


══════════════════════════════════════════════════════════════════════════════════════════════


    ┌────────────────────────────────────────────────────────────────────────────────────┐
    │                    4. OUTBOX — DoctorCreatedEvent                                  │
    └────────────────────────────────────────────────────────────────────────────────────┘


    DoctorService.create(...)
          │  (same @Transactional method, same DB transaction as the doctor+department save)
          ▼
    DoctorEventPublisher.publishDoctorCreated(doctorId, DoctorCreatedEvent(...), correlationId)
          │
          ▼
    OutboxWriter.enqueue(Topics.DOCTOR_EVENTS, key=doctorId, DomainEvent.of(...))
          │
          ▼
    doctor_db.outbox_events ──► OutboxRelay (common, polls the table) ──► Kafka: doctor.events
```

---

# 📦 Package Structure

```text
doctor-service/
└── src/main/java/.../doctor/
    │
    ├── web/
    │   ├── DoctorController
    │   └── dto/
    │       ├── DoctorRequest
    │       └── DoctorResponse
    │
    ├── service/
    │   └── DoctorService
    │
    ├── grpc/
    │   ├── DoctorLookupGrpcService
    │   └── DoctorDirectoryGrpcService
    │
    ├── event/
    │   └── DoctorEventPublisher
    │
    ├── repository/
    │   ├── DoctorRepository
    │   └── DepartmentRepository
    │
    └── domain/
        ├── Doctor
        ├── Department
        └── AvailabilitySlot
```

---

# 🗓️ Availability: Modeled, Not Yet Enforced

`AvailabilitySlot` is a recurring weekly window — a `DayOfWeek` plus a `startTime`/`endTime`
pair, cascade-owned by `Doctor` (`CascadeType.ALL, orphanRemoval = true`, so deleting a doctor
or clearing its `availability` set deletes the associated slots too). `Doctor.addAvailabilitySlot`
is the only way to attach one — it calls the package-private `AvailabilitySlot.assignTo(this)`,
so a slot can't exist without a doctor.

**What's real today:** the data model, and `Doctor.getAvailability()` returning the set.
**What isn't:** no endpoint on `DoctorController` creates, updates, or lists availability
slots, and nothing in `appointment-service`'s booking flow reads this table today —
appointment-service validates only that a doctor *exists* (via `DoctorLookup`), not that a
requested time falls inside one of their availability windows. The model is in place ahead of
the booking logic that would consume it.

---

# 🔌 Why Two gRPC Services, Not One

Same rationale as [`patient-service`](../patient-service/README.md#-why-two-grpc-services-not-one):
a narrow existence-check for `appointment-service`'s booking validation, a wide read for
`graphql-gateway`'s GraphQL field resolution — see
[ADR-0004](../../docs/adr/0004-grpc-for-internal-synchronous-calls.md). The one asymmetry
specific to this service: `DoctorDirectoryGrpcService.getDoctor` is `@Transactional(readOnly =
true)` and `DoctorLookupGrpcService.getDoctor` is not, because only the Directory response
touches `doctor.getDepartment()` — a lazy `@ManyToOne` association that requires an open
Hibernate session to resolve. Without the transaction, that access would throw once outside
the request scope, since this service runs with Hibernate's open-in-view disabled (see
`DoctorService`'s own `readOnly` transactional getters for the same reasoning applied to the
REST side).

---

# 🚀 Where This Is Headed

The gap between "availability is modeled" and "availability is enforced" is the natural next
step here: wiring `AvailabilitySlot` into the booking path so `appointment-service` can reject
a requested time that falls outside a doctor's declared hours, not just validate that the
doctor exists. The same per-caller-authorization gap that applies to `patient-service`'s gRPC
surface applies here too — both directory services are trusted by network boundary today, not
by caller identity.

---

See [`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) for the platform-wide communication model.
