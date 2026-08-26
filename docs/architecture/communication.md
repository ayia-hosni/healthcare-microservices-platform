# Service Communication

> Part of [Architecture](README.md). Deep dives: [04-communication-architecture.md](04-communication-architecture.md),
> [05-event-topology.md](05-event-topology.md).

The system intentionally uses different communication mechanisms depending on the consistency
and latency requirements of the workflow.

## External Communication

External clients communicate with the platform through the Ingress using REST, GraphQL, and
JWT-based authentication. Internal services are not exposed directly to the frontend.

## Internal Synchronous Communication

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

## Asynchronous Communication

Domain events are published through Kafka via a shared transactional outbox — see
[Transactional Outbox Pattern](../reliability/README.md#transactional-outbox-pattern) in
[`../reliability/README.md`](../reliability/README.md).

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

## Communication Decision

| Communication      | Technology     | Use Case                           |
| ------------------ | -------------- | ----------------------------------- |
| Client → Platform   | REST / GraphQL | Public APIs                        |
| Service → Service   | gRPC           | Internal synchronous, low-latency  |
| Service → Service   | REST           | Lower-volume synchronous calls     |
| Service → Service   | Kafka          | Domain events                      |
| Background Workers  | RabbitMQ       | Retryable asynchronous work        |

> **Principle:** Use synchronous communication when an immediate answer is required. Use asynchronous communication when downstream processing can happen independently.

---

## Appointment Booking Workflow

Appointment booking combines synchronous validation with asynchronous reactions, and is
protected by a request-rate limit (see
[Reliability: Rate Limiting & Circuit Breakers](../reliability/README.md#reliability-rate-limiting--circuit-breakers)
in [`../reliability/README.md`](../reliability/README.md)).

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
only patient/doctor validation and the DB write are on the synchronous path. See
[10-appointment-booking-sequence.md](10-appointment-booking-sequence.md) for the full sequence
diagram.

---

## Event-Driven Architecture

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
consumer scaling — see [05-event-topology.md](05-event-topology.md) for the full topic list
and topology, and [Supporting Services](supporting-services.md) for how the three event
consumers handle (and don't handle) redelivery.
