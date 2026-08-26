# 🏗️ Architecture

How the platform is put together: service boundaries, how they talk to each other, the core
booking workflow, and how data is owned. For reliability mechanisms (outbox, rate limiting,
auth, storage tech), see [`RELIABILITY.md`](RELIABILITY.md). For how to run and deploy it, see
[`OPERATIONS.md`](OPERATIONS.md). For implementation status, see [`PROGRESS.md`](PROGRESS.md).

---

## Table of Contents

* [Architecture](#-architecture)
* [Service Architecture](#-service-architecture)
* [Services](#-services)
* [Shared Modules](#-shared-modules)
* [Communication Architecture](#-communication-architecture)
* [Appointment Booking Workflow](#-appointment-booking-workflow)
* [Database-per-Service](#-database-per-service)
* [Concurrency & Idempotency](#-concurrency--idempotency)
* [Event-Driven Architecture](#-event-driven-architecture)
* [Architecture Decision Records](#-architecture-decision-records)

---

## 🏗️ Architecture

The platform separates **external API traffic**, **internal synchronous communication**, and **asynchronous domain events**.

This section covers the high-level shape of the system. The full documentation set — C4 system context, container architecture, per-domain service architecture, communication model, event topology, data architecture, security architecture, Kubernetes deployment architecture, observability architecture, and an end-to-end sequence diagram — lives under [`docs/architecture/`](docs/architecture/00-index.md).

```text
                                     ┌──────────────────────┐
                                     │      Frontend         │
                                     │     Angular SPA       │
                                     └──────────┬────────────┘
                                                │ HTTPS
                                                ▼
                                     ┌──────────────────────┐
                                     │    NGINX Ingress     │
                                     │  External API Entry  │
                                     └──────────┬────────────┘
                                                │
          ┌──────────────────┬──────────────────┼──────────────────┬───────────────────┐
          │                  │                  │                  │                   │
          ▼                  ▼                  ▼                  ▼                   ▼
   /api/v1/auth      /api/v1/patients   /api/v1/doctors  /api/v1/appointments   /graphql, /graphiql
          │                  │                  │                  │                   │
          ▼                  ▼                  ▼                  ▼                   ▼
 ┌────────────────┐ ┌────────────────┐ ┌────────────────┐ ┌────────────────┐ ┌────────────────────┐
 │    Identity    │ │    Patient     │ │     Doctor     │ │  Appointment   │ │   GraphQL Gateway   │
 │    Service     │ │    Service     │ │    Service     │ │    Service     │ │        (BFF)        │
 │     :8081      │ │     :8082      │ │     :8083      │ │     :8084      │ │        :8090        │
 └───────┬────────┘ └───────┬────────┘ └───────┬────────┘ └───────┬────────┘ └──────────┬───────────┘
         │                  │                  │                  │                     │
         ▼                  ▼                  ▼                  ▼                     │  no database —
  ┌──────────────┐   ┌──────────────┐   ┌──────────────┐   ┌──────────────┐              │  every field is
  │ identity_db  │   │  patient_db  │   │  doctor_db   │   │appointment_db│              │  fetched live over
  │ PostgreSQL   │   │ PostgreSQL   │   │ PostgreSQL   │   │ PostgreSQL   │              │  gRPC or REST
  └──────────────┘   └──────────────┘   └──────────────┘   └──────────────┘              │


    ─────────────────────────────────────────────────────────────────────────────────────
                          INTERNAL-ONLY SERVICES (no Ingress route)
    ─────────────────────────────────────────────────────────────────────────────────────

 ┌────────────────┐ ┌────────────────┐ ┌────────────────┐ ┌────────────────┐ ┌────────────────┐
 │  EMR Service    │ │ Billing Service│ │Notification Svc│ │  Audit Service │ │Analytics Service│
 │     :8085       │ │     :8086      │ │     :8087      │ │     :8088      │ │     :8089       │
 └────────┬────────┘ └───────┬────────┘ └───────┬────────┘ └───────┬────────┘ └────────┬────────┘
          ▼                  ▼                  ▼                  ▼                   ▼
   ┌──────────────┐  ┌──────────────┐  ┌────────────────┐ ┌──────────────┐  ┌───────────────┐
   │    emr_db    │  │  billing_db  │  │ notification_db│ │   audit_db   │  │  analytics_db  │
   │  PostgreSQL  │  │  PostgreSQL  │  │   PostgreSQL   │ │  PostgreSQL  │  │  PostgreSQL    │
   └──────────────┘  └──────────────┘  └────────────────┘ └──────────────┘  └───────────────┘

`emr-service`, `billing-service`, `notification-service`, `audit-service`, and `analytics-service`
are intentionally internal-only and expose no Ingress route today — they are reached through
gRPC/REST fan-out from other services or through Kafka event consumption (see ADR-0001).


    ─────────────────────────────────────────────────────────────────────────────────────
                              ASYNCHRONOUS EVENT LAYER
    ─────────────────────────────────────────────────────────────────────────────────────

                          ┌─────────────────────────┐
                          │   Appointment Service    │
                          └────────────┬─────────────┘
                                       │ AppointmentCreated / AppointmentCancelled
                                       │ (topic: appointment.events, via Outbox)
                                       ▼
                              ┌───────────────────┐
                              │   Apache Kafka     │
                              │   Event Stream     │
                              └─────────┬──────────┘
                                        │
              ┌─────────────────────────┼────────────────────────┐
              │                         │                        │
              ▼                         ▼                        ▼
     ┌─────────────────┐      ┌─────────────────┐       ┌──────────────────┐
     │ Billing Service  │      │  Audit Service   │       │Analytics Service │
     └────────┬─────────┘      └──────────────────┘       └──────────────────┘
              │ InvoiceGenerated / PaymentCompleted
              ▼      (topic: billing.events, back onto Kafka via its own Outbox)
            Kafka

   appointment-service (hourly AppointmentReminderJob, ~24h before a confirmed appointment)
   and emr-service both also publish onto Kafka topic notification.requests, consumed only
   by notification-service — which then hands delivery off to RabbitMQ:

                              ┌──────────────────┐
                              │Notification Svc   │──► RabbitMQ ──► Retry / DLQ
                              │      :8087        │              └─► Email / SMS / Push
                              └───────────────────┘

   `identity-service` touches neither Kafka nor RabbitMQ — it has no domain events today.
   RabbitMQ is used only inside notification-service; it is not a shared bus (ADR-0002).


    ─────────────────────────────────────────────────────────────────────────────────────
                               SHARED INFRASTRUCTURE
    ─────────────────────────────────────────────────────────────────────────────────────

       ┌───────────┐          ┌───────────────┐          ┌────────────────┐
       │   Redis    │          │ Elasticsearch │          │      MinIO      │
       │   Cache    │          │  Provisioned; │          │ Object Storage  │
       └─────▲──────┘          │  no indexing  │          └────────▲────────┘
             │                 │ consumers yet │                   │
   patient-service,            └───────────────┘             emr-service
   doctor-service                                        (clinical documents)


    ─────────────────────────────────────────────────────────────────────────────────────
                                 OBSERVABILITY
    ─────────────────────────────────────────────────────────────────────────────────────

       Spring Boot Services ──► Prometheus ──► Grafana
                            ──► Zipkin (tracing)
                            ──► Actuator ──► Kubernetes liveness / readiness
```

---

## 🧩 Service Architecture

The platform is organized around business domains rather than technical layers.

```text
                              HEALTHCARE PLATFORM
                                      │
        ┌─────────────────────────────┼─────────────────────────────┐
        │                             │                             │
        ▼                             ▼                             ▼
 ┌───────────────┐             ┌───────────────┐             ┌───────────────┐
 │ Identity &    │             │ Clinical      │             │ Scheduling    │
 │ Security      │             │ Domain        │             │ Domain        │
 └───────┬───────┘             └───────┬───────┘             └───────┬───────┘
         │                             │                             │
         ▼                             ▼                             ▼
    Identity Service              Patient Service              Appointment Service
                                  Doctor Service
                                  EMR Service


        ┌─────────────────────────────┼─────────────────────────────┐
        │                             │                             │
        ▼                             ▼                             ▼
 ┌───────────────┐             ┌───────────────┐             ┌───────────────┐
 │ Financial     │             │ Supporting    │             │ API / Edge    │
 │ Domain        │             │ Services      │             │ Layer         │
 └───────┬───────┘             └───────┬───────┘             └───────┬───────┘
         │                             │                             │
         ▼                             ▼                             ▼
    Billing Service              Notification Service         NGINX Ingress
                                 Audit Service                GraphQL Gateway
                                 Analytics Service
```

The three Supporting Services share a Kafka-consumer pattern and, with it, a shared set of
at-least-once delivery caveats — see [`SUPPORTING_SERVICES.md`](SUPPORTING_SERVICES.md).

---

## 🔌 Services

| Service                | Port | Responsibility                                                        |
| ----------------------- | ---: | --------------------------------------------------------------------- |
| `identity-service`     | 8081 | Registration, authentication, JWT issuance, refresh, logout, and RBAC |
| `patient-service`      | 8082 | Patient demographics, insurance, and medical history                  |
| `doctor-service`       | 8083 | Doctor profiles, departments, specialties, and availability           |
| `appointment-service`  | 8084 | Appointment booking, cancellation, rescheduling, and waiting lists    |
| `emr-service`          | 8085 | Encounters, diagnoses, medications, laboratory results, and allergies |
| `billing-service`      | 8086 | Invoices, payments, and payer eligibility checks                      |
| `notification-service` | 8087 | Asynchronous email, SMS, and push notification processing             |
| `audit-service`        | 8088 | Platform-wide append-only domain event auditing                       |
| `analytics-service`    | 8089 | Event-driven analytics and scheduled reporting                        |
| `graphql-gateway`      | 8090 | GraphQL BFF aggregating patient/doctor gRPC lookups and appointment/billing REST APIs behind one schema |

---

## 📚 Shared Modules

The platform separates reusable infrastructure from wire-level service contracts.

```text
                         ┌───────────────────┐
                         │   grpc-contracts   │
                         │                    │
                         │  .proto contracts  │
                         │  Generated stubs   │
                         │  Message classes   │
                         └─────────┬──────────┘
                                   │
                    ┌──────────────┴──────────────┐
                    │                             │
                    ▼                             ▼
             ┌──────────────┐              GraphQL Gateway
             │    common    │              (depends on grpc-contracts
             │              │               only — not on `common`)
             │ dto          │
             │ outbox       │
             │ cache        │
             │ security     │
             │ web          │
             │ events       │
             │ exception    │
             └──────┬───────┘
                    │
                    ▼
        The other nine Spring services
```

`common` provides the shared `OutboxWriter`/`OutboxEventRepository`/`OutboxRelay`, JWT
verification, Redis cache config, DTOs, domain events, and exception handling used across
services. `grpc-contracts` is split out separately so `graphql-gateway` — which deliberately
doesn't depend on `common` (see its `pom.xml`) — can pull in just the wire contracts without
inheriting `common`'s JPA/security/web dependencies. Both are library modules and are not
independently deployed.

---

## 🔄 Communication Architecture

The system intentionally uses different communication mechanisms depending on the consistency
and latency requirements of the workflow.

### External Communication

External clients communicate with the platform through the Ingress using REST, GraphQL, and
JWT-based authentication. Internal services are not exposed directly to the frontend.

### Internal Synchronous Communication

Patient Service and Doctor Service each expose two purpose-built gRPC services rather than one
general-purpose one — a narrow, single-caller contract beats a broad one serving several callers
with conflicting needs (ADR-0004):

```text
appointment-service
       ├──── gRPC PatientLookup (exists check) ────► patient-service
       └──── gRPC DoctorLookup  (exists check) ────► doctor-service

graphql-gateway
       ├──── gRPC PatientDirectory (full record) ──► patient-service
       ├──── gRPC DoctorDirectory  (full record) ──► doctor-service
       ├──── REST ──────────────────────────────────► appointment-service
       └──── REST ──────────────────────────────────► billing-service

emr-service
       └──── REST (role-based access) ─────────────► patient-service
```

`PatientLookup`/`DoctorLookup` answer a single question — "does this id exist?" — so
`appointment-service` can fail a booking fast on a bad `patientId`/`doctorId`.
`PatientDirectory`/`DoctorDirectory` are the wider read surface `graphql-gateway` needs to
resolve its GraphQL fields without a REST round trip. `graphql-gateway`'s remaining downstream
calls stay on REST for now (lower call volume; ADR-0004 scoped this pass to patient/doctor).
`emr-service` also still calls patient-service over REST because it needs per-caller
role-based access, which the (currently unauthenticated, cluster-trusted) gRPC directory
services don't yet support.

### Asynchronous Communication

Domain events are published through Kafka via a shared transactional outbox — see
[Transactional Outbox Pattern](RELIABILITY.md#-transactional-outbox-pattern) in
[`RELIABILITY.md`](RELIABILITY.md).

```text
appointment-service ──AppointmentCreated / AppointmentCancelled──► Kafka (appointment.events)
Kafka ──► billing-service (consumes appointment.events, produces billing.events back onto Kafka)
Kafka ──► audit-service
Kafka ──► analytics-service

notification-service ◄── Kafka (notification.requests) ◄── appointment-service (reminder job)
                                                        ◄── emr-service
notification-service ──► RabbitMQ ──► retry / DLQ ──► email / SMS / push
```

RabbitMQ is used **only inside** the Notification Service, for retry handling and dead-letter
queue processing — it is not a shared bus between services (ADR-0002).

### Communication Decision

| Communication      | Technology     | Use Case                           |
| ------------------ | -------------- | ----------------------------------- |
| Client → Platform   | REST / GraphQL | Public APIs                        |
| Service → Service   | gRPC           | Internal synchronous, low-latency  |
| Service → Service   | REST           | Lower-volume synchronous calls     |
| Service → Service   | Kafka          | Domain events                      |
| Background Workers  | RabbitMQ       | Retryable asynchronous work        |

> **Principle:** Use synchronous communication when an immediate answer is required. Use asynchronous communication when downstream processing can happen independently.

---

## 🩺 Appointment Booking Workflow

Appointment booking combines synchronous validation with asynchronous reactions, and is
protected by a request-rate limit (see
[Reliability: Rate Limiting & Circuit Breakers](RELIABILITY.md#-reliability-rate-limiting--circuit-breakers)
in [`RELIABILITY.md`](RELIABILITY.md)).

```text
Client
   │ POST /api/v1/appointments  (rate-limited: resilience4j @RateLimiter)
   ▼
Appointment Service
   ├──── gRPC PatientLookup ────► Patient Service  (validate patientId)
   ├──── gRPC DoctorLookup  ────► Doctor Service   (validate doctorId)
   ▼
Persist Appointment + Outbox row (single DB transaction)
   │
   ▼
appointment_db
   │
   │ OutboxRelay polls and publishes: AppointmentCreated
   ▼
Apache Kafka
   ├──────────────────────► Billing Service
   ├──────────────────────► Audit Service
   └──────────────────────► Analytics Service

(separately, ~24h before the appointment)
AppointmentReminderJob ──notification.requests──► Kafka ──► Notification Service ──► RabbitMQ ──► Email / SMS / Push
```

The client does not wait for billing, auditing, analytics, or the reminder job to complete —
only patient/doctor validation and the DB write are on the synchronous path.

---

## 🗄️ Database-per-Service

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
through shared persistence.

---

## ⚡ Concurrency & Idempotency

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

## 📡 Event-Driven Architecture

Business state changes are represented as domain events and distributed through Kafka.

```text
                     ┌─────────────────────┐
                     │ Appointment Service │
                     └──────────┬──────────┘
                                │ AppointmentCreated
                                ▼
                         ┌─────────────┐
                         │    Kafka    │
                         └──────┬──────┘
                                │
           ┌────────────────────┼─────────────────────┐
           │                    │                     │
           ▼                    ▼                     ▼
    Billing Service       Audit Service        Analytics Service
```

Consumers react independently to events and do not need to be directly invoked by the
producing service. This provides asynchronous processing, loose coupling, and independent
consumer scaling.

---

## 📝 Architecture Decision Records

Architectural decisions are documented under [`docs/adr/`](docs/adr/):

* [ADR-0001 — API Gateway / Ingress](docs/adr/0001-api-gateway-ingress.md)
* [ADR-0002 — Messaging Topology](docs/adr/0002-messaging-topology.md)
* [ADR-0003 — SOAP Payer Eligibility Integration](docs/adr/0003-soap-payer-eligibility-integration.md)
* [ADR-0004 — gRPC for Internal Synchronous Calls](docs/adr/0004-grpc-for-internal-synchronous-calls.md)

Each ADR documents the context, alternatives, decision, and consequences behind an
architectural choice.
