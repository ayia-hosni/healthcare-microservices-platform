# 🕸️ graphql-gateway

> **A stateless GraphQL BFF aggregating patient/doctor gRPC lookups and appointment/billing
> REST calls behind one schema.**

**Port:** `8090`
**Database:** none — every field is fetched live from the owning service
**Domain events:** none — this service is not a Kafka producer or consumer

The only service on the platform with no database and no Kafka involvement at all. Its entire
job is aggregation: turn one GraphQL query into the fan-out of gRPC/REST calls needed to answer
it, forward the caller's auth downstream unchanged, and never persist anything itself.

---

## ✨ Capabilities

| Capability                   | Description                                                                |
| -------------------------------| ------------------------------------------------------------------------ |
| 🔀 One schema, mixed transports | `patient`/`doctor` resolve over gRPC; `appointment`/`invoice` over REST  |
| 🪢 Nested field aggregation      | `appointment { patient { ... } }` costs one client round trip, not three |
| 🔑 Auth forwarding, not verification | The caller's JWT is relayed downstream unchanged; this gateway never validates or reissues it |
| ⏱️ Per-call gRPC deadlines       | 2-second deadline on every patient/doctor gRPC call                      |
| 🧵 String-typed date passthrough | Dates/decimals are relayed as raw wire values, not parsed and re-serialized |

---

## 🎯 What It Does

```graphql
type Query {
    patient(id: ID!): Patient
    doctor(id: ID!): Doctor
    appointment(id: ID!): Appointment
    invoice(id: ID!): Invoice
}

type Mutation {
    bookAppointment(input: BookAppointmentInput!): Appointment!
}
```

Exposed at `/graphql` (and `/graphiql` for interactive exploration). `Appointment.patient` and
`Appointment.doctor` are the schema's actual aggregation value — the two fields that fold what
would otherwise be three separate client requests (fetch the appointment, then separately fetch
its patient, then its doctor) into one.

---

# 🏗️ Architecture

Four data fetchers, two transports, one shared auth-forwarding mechanism.

```text
══════════════════════════════════════════════════════════════════════════════════════════════
                                   GRAPHQL GATEWAY
══════════════════════════════════════════════════════════════════════════════════════════════


    ┌────────────────────────────────────────────────────────────────────────────────────┐
    │                    1. AUTH FORWARDING — EVERY REQUEST, FIRST                       │
    └────────────────────────────────────────────────────────────────────────────────────┘


        Client
          │
          │ POST /graphql   Authorization: Bearer <jwt>
          ▼
    ┌──────────────────────────────────────────────────────┐
    │              AuthHeaderInterceptor                     │
    │              (WebGraphQlInterceptor)                   │
    │                                                        │
    │  copies the Authorization header into the GraphQL       │
    │  execution context — this gateway NEVER verifies or     │
    │  reissues the token itself                              │
    └──────────────────┬─────────────────────────────────────┘
                       │
                       ▼
          @ContextValue(AUTHORIZATION_CONTEXT_KEY) — every data
          fetcher below reads it back out and forwards it via
          DownstreamAuth.addTo(headers, authorization)
                       │
                       ▼
          Each downstream service (appointment-service,
          billing-service) runs its OWN JWT verification exactly
          as it would for a direct caller — shared verification
          logic (common's JwtVerifier), not shared trust


══════════════════════════════════════════════════════════════════════════════════════════════


    ┌────────────────────────────────────────────────────────────────────────────────────┐
    │              2. PATIENT / DOCTOR — gRPC, NOT REST                                 │
    └────────────────────────────────────────────────────────────────────────────────────┘


    query { patient(id: "...") { ... } }        query { doctor(id: "...") { ... } }
              │                                            │
              ▼                                            ▼
    ┌───────────────────────┐                  ┌───────────────────────┐
    │   PatientDataFetcher   │                  │   DoctorDataFetcher   │
    └───────────┬───────────┘                  └───────────┬───────────┘
               │                                            │
               ▼                                            ▼
    ┌────────────────────────────┐              ┌────────────────────────────┐
    │  PatientLookupGrpcClient   │              │  DoctorLookupGrpcClient    │
    │  ⚠️ NAME IS MISLEADING —    │              │  ⚠️ NAME IS MISLEADING —    │
    │     see box below           │              │     see box below           │
    │                             │              │                             │
    │  2s deadline                │              │  2s deadline                │
    └─────────────┬───────────────┘              └─────────────┬───────────────┘
                  │ gRPC PatientDirectory.getPatient()          │ gRPC DoctorDirectory.getDoctor()
                  ▼                                             ▼
           patient-service                              doctor-service
           PatientDirectoryGrpcService                   DoctorDirectoryGrpcService
           (the FULL-RECORD contract —                   (the FULL-RECORD contract —
            not the exists-check                          not the exists-check
            appointment-service uses)                      appointment-service uses)


    ┌──────────────────────────────────────────────────────────────────────────────┐
    │  Despite the class name "...LookupGrpcClient", both clients call the         │
    │  DIRECTORY contract (PatientDirectoryGrpc / DoctorDirectoryGrpc) — the wide,  │
    │  full-record read — NOT the narrower "Lookup" existence-check contract that  │
    │  appointment-service's BookingValidationClient calls. The class names are    │
    │  historical, not descriptive of which gRPC service they actually invoke.     │
    └──────────────────────────────────────────────────────────────────────────────┘


══════════════════════════════════════════════════════════════════════════════════════════════


    ┌────────────────────────────────────────────────────────────────────────────────────┐
    │              3. APPOINTMENT / INVOICE — REST, PLUS NESTED gRPC                    │
    └────────────────────────────────────────────────────────────────────────────────────┘


    query { appointment(id: "...") {                    query { invoice(id: "...") { ... } }
        id status patient { firstName } doctor { ... }              │
    } }                                                              ▼
              │                                             ┌───────────────────────┐
              ▼                                             │   InvoiceDataFetcher   │
    ┌───────────────────────────┐                            └───────────┬───────────┘
    │  AppointmentDataFetcher    │                                        │
    └─────────────┬───────────────┘                             GET /api/v1/invoices/{id}
                  │                                              (forwarded Authorization)
        GET /api/v1/appointments/{id}                                    │
        (forwarded Authorization)                                        ▼
                  │                                             billing-service
                  ▼                                             404 ──► return null
         appointment-service
         404 ──► return null (not an error)
                  │
                  │  AppointmentDto returned — now resolving
                  │  its NESTED fields, each a SEPARATE call:
                  │
        ┌──────────┴──────────┐
        ▼                     ▼
    @SchemaMapping        @SchemaMapping
    (Appointment.patient)  (Appointment.doctor)
        │                     │
        ▼                     ▼
    PatientLookupGrpcClient  DoctorLookupGrpcClient
    .fetch(patientId)         .fetch(doctorId)
        │                     │
        ▼                     ▼
    patient-service          doctor-service
    (gRPC Directory)         (gRPC Directory)

    ⚠️ NOT batched — see Advanced below. A query resolving both
       Appointment.patient and Appointment.doctor makes 1 REST
       call + 2 separate gRPC calls: three round trips total,
       still fewer than the client making three of its own.


══════════════════════════════════════════════════════════════════════════════════════════════


    ┌────────────────────────────────────────────────────────────────────────────────────┐
    │                    4. MUTATION — bookAppointment                                   │
    └────────────────────────────────────────────────────────────────────────────────────┘


    mutation { bookAppointment(input: {...}) { id status } }
              │
              ▼
    ┌───────────────────────────┐
    │  AppointmentDataFetcher    │
    │  .bookAppointment(input)   │
    └─────────────┬───────────────┘
                  │
                  ▼
        POST /api/v1/appointments
        (forwarded Authorization, BookAppointmentInput
         relayed to appointment-service as-is — see the
         DTO note below)
                  │
                  ▼
         appointment-service (full booking flow —
         see its own README: gRPC pre-validation,
         idempotency, rate limiting, outbox)
```

---

# 📦 Package Structure

```text
graphql-gateway/
└── src/main/java/.../gateway/
    │
    ├── web/
    │   ├── PatientDataFetcher
    │   ├── DoctorDataFetcher
    │   ├── AppointmentDataFetcher    — query, mutation, AND two @SchemaMapping resolvers
    │   ├── InvoiceDataFetcher
    │   └── DownstreamAuth            — package-private helper, shared by all four fetchers
    │
    ├── grpc/
    │   ├── PatientLookupGrpcClient   — actually calls PatientDirectory (see above)
    │   └── DoctorLookupGrpcClient    — actually calls DoctorDirectory (see above)
    │
    ├── config/
    │   ├── AuthHeaderInterceptor     — WebGraphQlInterceptor, runs on every request
    │   ├── RestClientConfig          — one RestClient bean per REST-backed downstream
    │   └── DownstreamServicesProperties
    │
    └── dto/
        ├── PatientDto / DoctorDto
        ├── AppointmentDto / BookAppointmentInput
        └── InvoiceDto

src/main/resources/graphql/
    └── schema.graphqls
```

No `service`, `repository`, or `domain` package — every field is resolved live from a
downstream call, so there's nothing to persist or hold business logic over.

---

# 🔀 One Schema, Two Transports — And Why

```text
patient / doctor        ──► gRPC   (moved off REST per ADR-0004; PatientDirectory/
                                    DoctorDirectory exist specifically for this gateway)

appointment / invoice   ──► REST   (appointment-service and billing-service expose
                                    no gRPC server — these stay REST clients of the
                                    exact same endpoints the frontend would otherwise
                                    call directly)
```

`RestClientConfig`'s own class comment states this plainly: patient/doctor lookups moved to
gRPC; appointment/billing didn't, purely because those two services don't have a gRPC surface
to move onto yet — not because REST was judged a better fit for them.

---

# ⚠️ Advanced: Not Batched, and Why That's Fine For Now

`AppointmentDataFetcher.patient(...)` and `.doctor(...)` are plain `@SchemaMapping` methods,
each making its own independent gRPC call — there's no `DataLoader` batching N pending
`patient` lookups into a single request the way a GraphQL server serving a *list* of
appointments typically would. The class's own comment is explicit that this is a scoped
decision, not an oversight: the schema today has no `appointments: [Appointment]` list field,
only a single `appointment(id: ID!)` query — so there's never more than one `patient` and one
`doctor` resolution happening per request, and nothing for a DataLoader to batch. The moment a
list-returning query is added to the schema, this is the first thing that needs to change,
because N appointments would otherwise mean up to 2N gRPC calls per query.

---

# 🚀 Where This Is Headed

The REST calls to appointment-service and billing-service move onto gRPC directory-style
contracts the same way patient/doctor already did, once each of those services exposes one —
closing the gap between "some downstream calls are gRPC" and "all of them are." If a
list-returning query is ever added to the schema, DataLoader batching on the nested
`patient`/`doctor` resolvers becomes necessary at that point, not optional.

---

See [`../../docs/architecture/README.md`](../../docs/architecture/README.md) for the full communication model.
