# Sequence: Appointment Booking

The appointment-booking workflow demonstrates the platform's core communication pattern:

**synchronous validation → atomic persistence → transactional outbox → asynchronous event processing**

The client receives the booking response without waiting for billing, audit, analytics, or notification processing.

---

## Table of Contents

1. [Workflow Overview](#1-workflow-overview)
2. [Appointment Booking Flow](#2-appointment-booking-flow)
3. [Transactional Persistence](#3-transactional-persistence)
4. [Asynchronous Event Processing](#4-asynchronous-event-processing)
5. [Reminder Notification Flow](#5-reminder-notification-flow)
6. [Failure Boundaries](#6-failure-boundaries)
7. [Why the Workflow Is Designed This Way](#7-why-the-workflow-is-designed-this-way)
8. [Concurrency and Idempotency](#8-concurrency-and-idempotency)
9. [Related Documentation](#9-related-documentation)

---

# 1. Workflow Overview

```text
                         APPOINTMENT BOOKING
                                │
                                ▼
                         Client Request
                                │
                                ▼
                         NGINX Ingress
                                │
                                ▼
                    appointment-service
                                │
                 ┌──────────────┴──────────────┐
                 ▼                             ▼
        Patient Validation             Doctor Validation
                 │                             │
                 └──────────────┬──────────────┘
                                ▼
                         Atomic Transaction
                                │
                   ┌────────────┴────────────┐
                   ▼                         ▼
             appointment_db             Outbox Table
                   │                         │
                   └────────────┬────────────┘
                                ▼
                           201 Created
                                │
                                ▼
                         OutboxRelay
                                │
                                ▼
                              Kafka
                                │
             ┌──────────────────┼──────────────────┐
             ▼                  ▼                  ▼
          Billing              Audit            Analytics
             │
             ▼
      InvoiceGeneratedEvent
             │
             ▼
            Kafka
```

The important boundary is the response:

```text
Synchronous path
──────────────────────────────────────────────►

Client
  │
  ▼
Ingress
  │
  ▼
Appointment Service
  │
  ├──► Patient validation
  │
  ├──► Doctor validation
  │
  └──► DB + Outbox transaction
             │
             ▼
        201 Created
             │
             ▼
          Client


Asynchronous path
──────────────────────────────────────────────►

OutboxRelay
    │
    ▼
  Kafka
    │
    ├──► Billing
    ├──► Audit
    └──► Analytics
```

---

# 2. Appointment Booking Flow

```text
┌──────────────────┐
│      Client      │
└────────┬─────────┘
         │
         │ POST /api/v1/appointments
         ▼
┌──────────────────┐
│  NGINX Ingress   │
└────────┬─────────┘
         │
         ▼
┌────────────────────────┐
│ appointment-service    │
└───────────┬────────────┘
            │
       ┌────┴─────┐
       ▼          ▼
┌────────────┐ ┌────────────┐
│  Patient   │ │   Doctor   │
│  Service   │ │   Service  │
└─────┬──────┘ └─────┬──────┘
      │              │
      │ gRPC         │ gRPC
      │ exists       │ exists
      └──────┬───────┘
             ▼
      ┌──────────────┐
      │   Persist    │
      │ Appointment  │
      └──────┬───────┘
             │
       ┌─────┴─────┐
       ▼           ▼
┌────────────┐ ┌────────────┐
│Appointment │ │   Outbox   │
│    DB      │ │    Table   │
└────────────┘ └─────┬──────┘
                     │
                     ▼
              ┌────────────┐
              │    201     │
              │  Created   │
              └─────┬──────┘
                    │
                    ▼
                 Client
```

The synchronous request path performs only the work required to safely accept or reject the appointment.

---

# 3. Transactional Persistence

The appointment and its domain event are persisted within the same transaction.

```text
                 @Transactional
                       │
                       ▼
              ┌─────────────────┐
              │ appointment_db  │
              │                 │
              │ Appointment     │
              │       +         │
              │ OutboxEvent     │
              └────────┬────────┘
                       │
                 Atomic Commit
                       │
              ┌────────┴────────┐
              │                 │
              ▼                 ▼
        Appointment        Event available
         persisted          for relay
```

The key invariant is:

```text
Appointment committed
        +
AppointmentCreatedEvent committed
        │
        ▼
      ALWAYS
      TOGETHER
```

There is no separate:

```text
INSERT appointment
      │
      ▼
publish Kafka
```

operation in the request thread.

Instead:

```text
DB Transaction
     │
     ├── Appointment INSERT
     │
     └── OutboxEvent INSERT
             │
             ▼
        Atomic Commit
             │
             ▼
        OutboxRelay
             │
             ▼
           Kafka
```

This prevents the classic failure where an appointment is successfully stored but its corresponding event is lost because Kafka was unavailable.

---

# 4. Asynchronous Event Processing

Once the appointment transaction commits, `OutboxRelay` publishes the event to Kafka.

```text
                Outbox Table
                     │
                     │ poll
                     ▼
                OutboxRelay
                     │
                     │ publish
                     ▼
                  Kafka
                     │
        ┌────────────┼────────────┐
        │            │            │
        ▼            ▼            ▼
     Billing       Audit       Analytics
        │            │            │
        ▼            ▼            ▼
   billing_db    audit_db    analytics_db
```

The consumers operate independently.

```text
                     Kafka
                       │
       ┌───────────────┼────────────────┐
       │               │                │
       ▼               ▼                ▼
    Billing          Audit          Analytics
       │
       │ generate invoice
       ▼
InvoiceGeneratedEvent
       │
       ▼
     Kafka
       │
       ├──────────────► Audit
       │
       └──────────────► Analytics
```

This is a **choreographed distributed workflow** rather than a centrally orchestrated workflow.

There is no:

```text
Booking Orchestrator
        │
        ├── call Billing
        ├── call Audit
        └── call Analytics
```

Instead:

```text
Appointment
     │
     ▼
   Event
     │
     ├──► Billing
     ├──► Audit
     └──► Analytics
```

Each service reacts independently to events relevant to its responsibility.

---

# 5. Reminder Notification Flow

Appointment reminders are intentionally separated from the booking transaction.

`notification-service` does **not** consume `AppointmentCreatedEvent` to send the reminder.

Instead, `AppointmentReminderJob` periodically searches for upcoming confirmed appointments.

```text
             appointment-service
                     │
                     ▼
             AppointmentReminderJob
                     │
                  Hourly
                     │
                     ▼
        Find CONFIRMED appointments
             starting in 23–25h
                     │
                     ▼
          NotificationRequestedEvent
                     │
                     ▼
                   Kafka
                     │
                     ▼
          notification-service
                     │
                     ▼
                RabbitMQ
                     │
              ┌──────┴──────┐
              ▼             ▼
           Delivery       Failure
              │             │
              ▼             ▼
          Completed       Retry
                            │
                            ▼
                           DLQ
```

The distinction is important:

```text
AppointmentCreatedEvent
        │
        ├──► Billing
        ├──► Audit
        └──► Analytics


Later...

AppointmentReminderJob
        │
        ▼
NotificationRequestedEvent
        │
        ▼
Notification Service
        │
        ▼
RabbitMQ
```

A successful appointment booking therefore does **not** depend on notification delivery.

---

# 6. Failure Boundaries

The workflow deliberately creates clear failure boundaries.

```text
                     BOOKING REQUEST
                           │
                           ▼
                    Validation Layer
                     │           │
                  failure      success
                     │           │
                     ▼           ▼
                  Reject       Persist
                                │
                         ┌──────┴──────┐
                         │             │
                      DB fail       DB commit
                         │             │
                         ▼             ▼
                       Reject       201 Created
                                       │
                                       ▼
                                  Outbox Relay
                                       │
                                  ┌────┴────┐
                                  │         │
                              Kafka OK   Kafka down
                                  │         │
                                  ▼         ▼
                              Consumers   Retry later
```

### Patient / doctor validation failure

```text
Invalid patient
      │
      ▼
gRPC lookup fails
      │
      ▼
BusinessException
      │
      ▼
Booking rejected
```

Nothing is persisted.

### Database failure

```text
Transaction fails
      │
      ▼
Appointment + Outbox rollback
      │
      ▼
No 201 response
```

### Kafka failure after commit

```text
Appointment + Outbox
        │
        ▼
     Committed
        │
        ▼
    Kafka unavailable
        │
        ▼
 Outbox remains unpublished
        │
        ▼
 Relay can retry
```

The booking itself therefore does not depend on Kafka being immediately available.

---

# 7. Why the Workflow Is Designed This Way

## Immediate validation

Patient and doctor existence are validated synchronously because the client needs a definitive answer before the appointment is accepted.

```text
Appointment
    │
    ├──► PatientLookup
    │
    └──► DoctorLookup
             │
             ▼
       Validation result
             │
             ▼
        Accept / Reject
```

Both lookups use short deadlines so an unavailable dependency does not leave the booking request hanging indefinitely.

---

## Atomic persistence

The appointment and `AppointmentCreatedEvent` are written in one transaction.

```text
┌───────────────────────────┐
│       DB TRANSACTION      │
│                           │
│  Appointment INSERT       │
│           +               │
│  OutboxEvent INSERT       │
│                           │
└─────────────┬─────────────┘
              │
              ▼
          COMMIT
```

This is the transactional outbox boundary.

---

## Fast client response

The client does not wait for downstream consumers.

```text
Client
  │
  ▼
Booking
  │
  ├── Validate
  ├── Persist
  └── Outbox
        │
        ▼
   201 Created
        │
        ▼
     Client
```

Only after that:

```text
Outbox
  │
  ▼
Kafka
  │
  ├──► Billing
  ├──► Audit
  └──► Analytics
```

---

## Independent consumers

Each consumer can process the event according to its own availability and processing speed.

```text
                  AppointmentCreated
                         │
                         ▼
                       Kafka
                         │
        ┌────────────────┼────────────────┐
        ▼                ▼                ▼
     Billing           Audit          Analytics
        │                │                │
   Invoice logic     Audit logic     Reporting logic
```

One consumer's latency does not become part of the booking request latency.

---

## Choreographed billing

Billing reacts to the appointment event and then publishes its own result.

```text
Appointment
     │
     ▼
AppointmentCreatedEvent
     │
     ▼
   Billing
     │
     ▼
InvoiceGeneratedEvent
     │
     ▼
   Kafka
     │
     ├──► Audit
     └──► Analytics
```

There is no central transaction coordinator or workflow orchestrator.

---

# 8. Concurrency and Idempotency

The sequence diagram intentionally focuses on communication rather than database concurrency controls.

The actual persistence path must also protect against duplicate or concurrent booking attempts.

```text
                 Concurrent Requests
                  │            │
                  ▼            ▼
              Request A    Request B
                  │            │
                  └──────┬─────┘
                         ▼
                  appointment-service
                         │
                         ▼
                  Concurrency /
                  idempotency controls
                         │
                         ▼
                  appointment_db
```

The persistence boundary is therefore:

```text
Validate
   │
   ▼
Concurrency-safe persistence
   │
   ▼
Appointment + Outbox
   │
   ▼
Commit
```

For the detailed implementation and protection against duplicate/concurrent requests, see the platform's **Concurrency & Idempotency** documentation.

---

# 9. Related Documentation

| Document                                                       | Purpose                                         |
| -------------------------------------------------------------- | ----------------------------------------------- |
| [Communication Architecture](04-communication-architecture.md) | REST, gRPC, Kafka, RabbitMQ and SOAP decisions  |
| [Event Topology](05-event-topology.md)                         | Kafka topics, producers and consumers           |
| [Data Architecture](06-data-architecture.md)                   | Database ownership and transactional boundaries |
| [Service Architecture](03-service-architecture.md)             | Responsibilities of each service                |
| [Security Architecture](07-security-architecture.md)           | Authentication and authorization                |
| [Deployment Architecture](08-deployment-architecture.md)       | Kubernetes runtime architecture                 |
| [Observability Architecture](09-observability-architecture.md) | Metrics, health and tracing                     |
| [ADR-0003](../adr/0003-soap-payer-eligibility-integration.md)  | External payer integration                      |
| [ADR-0004](../adr/0004-grpc-for-internal-synchronous-calls.md) | Internal gRPC decisions                         |

> **Key architectural pattern:** Appointment booking keeps the client-facing path short and deterministic. Patient/doctor validation happens synchronously, the appointment and its outbox event are committed atomically, and everything downstream is asynchronous. Billing, audit, analytics, and reminder notifications therefore remain operationally decoupled from the booking request itself.
