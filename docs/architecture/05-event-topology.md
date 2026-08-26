# Event Topology

Kafka is the platform's **cross-service event backbone**. Domain services publish events through the shared transactional outbox implementation in `common`, using `OutboxEvent`, `OutboxWriter`, and `OutboxRelay`.

The event architecture separates **business events** from **notification tasks** and keeps downstream services loosely coupled.

---

## Table of Contents

1. [Event Architecture](#1-event-architecture)
2. [Event Flow](#2-event-flow)
3. [Kafka Topics](#3-kafka-topics)
4. [Domain Event Producers](#4-domain-event-producers)
5. [Event Consumers](#5-event-consumers)
6. [Appointment Booking Event Flow](#6-appointment-booking-event-flow)
7. [Billing Event Flow](#7-billing-event-flow)
8. [Notification Event Flow](#8-notification-event-flow)
9. [Transactional Outbox](#9-transactional-outbox)
10. [Event Ownership](#10-event-ownership)
11. [RabbitMQ Boundary](#11-rabbitmq-boundary)
12. [Delivery Semantics](#12-delivery-semantics)
13. [Known Gaps](#13-known-gaps)
14. [Related Documentation](#14-related-documentation)

---

# 1. Event Architecture

```text
╔══════════════════════════════════════════════════════════════════════╗
║                       DOMAIN SERVICES                               ║
╚══════════════════════════════════════════════════════════════════════╝
        │                 │                 │                 │
        │                 │                 │                 │
        ▼                 ▼                 ▼                 ▼
   Patient Events    Doctor Events    Appointment Events   EMR Events
        │                 │                 │                 │
        └─────────────────┴─────────────────┴─────────────────┘
                                  │
                                  ▼
                     ┌────────────────────────┐
                     │   TRANSACTIONAL        │
                     │       OUTBOX            │
                     │                        │
                     │ OutboxEvent            │
                     │ OutboxWriter           │
                     │ OutboxRelay            │
                     └────────────┬───────────┘
                                  │
                                  ▼
                         ╔════════════════╗
                         ║     KAFKA      ║
                         ║ Event Backbone ║
                         ╚═══════╤════════╝
                                 │
          ┌──────────────────────┼──────────────────────┐
          │                      │                      │
          ▼                      ▼                      ▼
      Billing              Notification              Audit
      Service                 Service               Service
                                 │
                                 ▼
                            RabbitMQ
                                 │
                                 ▼
                          Retry / DLQ
                                 
                                 ┌─────────────────────┐
                                 │     Analytics       │
                                 │       Service       │
                                 └─────────────────────┘
```

The core model is:

```text
Domain Transaction
       │
       ├──► Business Data
       │
       └──► Outbox Event
                │
                ▼
         Outbox Relay
                │
                ▼
              Kafka
                │
       ┌────────┼────────┐
       ▼        ▼        ▼
    Service  Service  Service
```

---

# 2. Event Flow

The platform has three distinct event paths.

```text
                         EVENT FLOWS

┌─────────────────────────────────────────────────────────────────┐
│  1. DOMAIN EVENTS                                                │
│                                                                 │
│  Service ──► Transactional Outbox ──► Kafka ──► Consumers       │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  2. BILLING EVENTS                                               │
│                                                                 │
│  Appointment ──► Kafka ──► Billing                              │
│                              │                                  │
│                              └──► billing.events ──► Kafka       │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  3. NOTIFICATION TASKS                                           │
│                                                                 │
│  Domain Service ──► notification.requests ──► Notification      │
│                                                   │              │
│                                                   ▼              │
│                                               RabbitMQ           │
│                                                   │              │
│                                           Retry / Delivery / DLQ  │
└─────────────────────────────────────────────────────────────────┘
```

This distinction is important: **Kafka carries cross-service domain events, while RabbitMQ handles notification delivery tasks inside `notification-service`.**

---

# 3. Kafka Topics

```text
                         ╔══════════════════╗
                         ║      KAFKA       ║
                         ╚════════╤═════════╝
                                  │
      ┌───────────────┬───────────┼────────────┬─────────────────┐
      │               │           │            │                 │
      ▼               ▼           ▼            ▼                 ▼
patient.events   doctor.events  appointment   emr.events    billing.events
                                .events
      │               │           │            │                 │
      │               │           │            │                 │
      └───────┬───────┴─────┬─────┴──────┬─────┘                 │
              │             │            │                        │
              ▼             ▼            ▼                        ▼
            Audit       Analytics     Billing                   Audit
            Service       Service     Service                  Analytics
```

### Topic ownership

| Topic                   | Producer(s)                          | Consumer(s)                                             | Purpose                       |
| ----------------------- | ------------------------------------ | ------------------------------------------------------- | ----------------------------- |
| `patient.events`        | `patient-service`                    | `audit-service`, `analytics-service`                    | Patient lifecycle events      |
| `doctor.events`         | `doctor-service`                     | `audit-service`, `analytics-service`                    | Doctor lifecycle events       |
| `appointment.events`    | `appointment-service`                | `billing-service`, `audit-service`, `analytics-service` | Appointment lifecycle         |
| `emr.events`            | `emr-service`                        | `audit-service`, `analytics-service`                    | Clinical/EMR lifecycle        |
| `billing.events`        | `billing-service`                    | `audit-service`, `analytics-service`                    | Billing lifecycle             |
| `notification.requests` | `appointment-service`, `emr-service` | `notification-service`                                  | Notification requests         |
| `audit.events`          | None                                 | None                                                    | Declared but currently unused |

---

# 4. Domain Event Producers

Each domain service owns the events generated by its own business transactions.

```text
┌───────────────────────┐
│   patient-service     │
└──────────┬────────────┘
           │
           ▼
   PatientRegisteredEvent


┌───────────────────────┐
│    doctor-service     │
└──────────┬────────────┘
           │
           ▼
     DoctorCreatedEvent


┌──────────────────────────┐
│   appointment-service    │
└────────────┬─────────────┘
             │
       ┌─────┴─────────────┐
       ▼                   ▼
AppointmentCreated   AppointmentCancelled
       │
       ▼
NotificationRequestedEvent


┌───────────────────────┐
│      emr-service      │
└──────────┬────────────┘
           │
     ┌─────┴────────────────────────┐
     ▼                              ▼
PrescriptionCreatedEvent   DocumentReferenceCreatedEvent
     │
     ▼
NotificationRequestedEvent


┌───────────────────────┐
│    billing-service    │
└──────────┬────────────┘
           │
      ┌────┴─────┐
      ▼          ▼
InvoiceGenerated  PaymentCompleted
```

### Representative event types

| Producer              | Event                           |
| --------------------- | ------------------------------- |
| `patient-service`     | `PatientRegisteredEvent`        |
| `doctor-service`      | `DoctorCreatedEvent`            |
| `appointment-service` | `AppointmentCreatedEvent`       |
| `appointment-service` | `AppointmentCancelledEvent`     |
| `appointment-service` | `NotificationRequestedEvent`    |
| `emr-service`         | `PrescriptionCreatedEvent`      |
| `emr-service`         | `DocumentReferenceCreatedEvent` |
| `emr-service`         | `NotificationRequestedEvent`    |
| `billing-service`     | `InvoiceGeneratedEvent`         |
| `billing-service`     | `PaymentCompletedEvent`         |

`identity-service` does not currently participate in Kafka.

---

# 5. Event Consumers

The consumer architecture is intentionally asymmetric.

```text
                     ╔══════════════════╗
                     ║      KAFKA       ║
                     ╚════════╤═════════╝
                              │
          ┌───────────────────┼────────────────────┐
          │                   │                    │
          ▼                   ▼                    ▼
   ┌─────────────┐     ┌─────────────┐      ┌──────────────┐
   │   Billing   │     │ Notification│      │    Audit     │
   │   Service   │     │   Service   │      │   Service    │
   └──────┬──────┘     └─────────────┘      └──────────────┘
          │
          ▼
   billing.events
          │
          └──────────────────────────────►
                                         Kafka
                                           │
                                  ┌────────┴────────┐
                                  ▼                 ▼
                               Audit            Analytics
```

### Consumer responsibilities

**Billing**

Consumes appointment events and creates billing-side business events.

**Notification**

Consumes notification requests and turns them into delivery tasks.

**Audit**

Consumes domain events and maintains an append-oriented audit trail.

**Analytics**

Consumes domain events and builds event-driven reporting/metrics.

---

# 6. Appointment Booking Event Flow

Appointment booking is the primary choreography example in the platform.

```text
                    APPOINTMENT BOOKING

┌──────────────────────┐
│      Client          │
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│ appointment-service  │
└──────────┬───────────┘
           │
           │ Synchronous validation
           ├──────────────► patient-service
           │
           ├──────────────► doctor-service
           │
           ▼
     Persist Appointment
           │
           ▼
    Write Outbox Event
           │
           ▼
   AppointmentCreatedEvent
           │
           ▼
      ╔═══════════╗
      ║   Kafka   ║
      ╚═════╤═════╝
            │
      ┌─────┼──────────────┬──────────────┐
      │     │              │              │
      ▼     ▼              ▼              ▼
   Billing Notification   Audit       Analytics
      │       │
      │       ▼
      │   RabbitMQ
      │       │
      ▼       ▼
   Invoice   Delivery
```

The appointment service does not directly invoke billing, audit, or analytics.

Instead, the appointment event becomes the integration point.

---

# 7. Billing Event Flow

`billing-service` is both a **consumer and producer**.

```text
┌────────────────────────┐
│ appointment-service    │
└───────────┬────────────┘
            │
            │ AppointmentCreatedEvent
            ▼
       ╔══════════╗
       ║  Kafka   ║
       ╚════╤═════╝
            │
            ▼
┌────────────────────────┐
│    billing-service     │
└───────────┬────────────┘
            │
            ▼
      Generate Invoice
            │
            ▼
     InvoiceGeneratedEvent
            │
            ▼
       ╔══════════╗
       ║  Kafka   ║
       ╚════╤═════╝
            │
       ┌────┴─────┐
       ▼          ▼
     Audit     Analytics
```

The same pattern applies to payment events:

```text
PaymentCompleted
       │
       ▼
 billing.events
       │
 ┌─────┴─────┐
 ▼           ▼
Audit     Analytics
```

This is a **choreographed workflow**: services react to events and publish their own outcomes rather than relying on a central workflow orchestrator.

---

# 8. Notification Event Flow

Notification requests follow a separate path.

```text
┌──────────────────────┐
│ appointment-service  │
└──────────┬───────────┘
           │
           │ NotificationRequestedEvent
           ▼
      ╔══════════════╗
      ║    Kafka     ║
      ╚══════╤═══════╝
             │
             ▼
┌────────────────────────┐
│ notification-service   │
└───────────┬────────────┘
            │
            ▼
       ╔══════════╗
       ║ RabbitMQ ║
       ╚════╤═════╝
            │
       ┌────┴─────┐
       ▼          ▼
   Delivery     Retry
       │          │
       │          ▼
       │         DLQ
       │
       ▼
 Email / SMS / Push
```

The same notification pipeline can be triggered by EMR events.

```text
emr-service
     │
     ▼
notification.requests
     │
     ▼
notification-service
     │
     ▼
RabbitMQ
     │
     ▼
Delivery
```

---

# 9. Transactional Outbox

Events are not published directly from the business transaction.

The shared `common` module provides the transactional outbox pattern.

```text
                BUSINESS TRANSACTION

┌───────────────────────────────────────────┐
│             Service Database              │
│                                           │
│  ┌────────────────┐   ┌────────────────┐  │
│  │ Business Data  │   │ OutboxEvent    │  │
│  │                │   │                │  │
│  │ Appointment    │   │ Event Payload  │  │
│  │ Invoice        │   │ Event Type     │  │
│  │ Patient        │   │ Status         │  │
│  └────────┬───────┘   └───────┬────────┘  │
│           │                   │           │
│           └─────────┬─────────┘           │
│                     │                     │
│                  COMMIT                   │
└─────────────────────┬─────────────────────┘
                      │
                      ▼
               OutboxRelay
                      │
                      ▼
                   Kafka
```

The important property is:

```text
Database transaction
        │
        ├── Business state
        │
        └── Outbox event
                │
              COMMIT
                │
                ▼
          Outbox Relay
                │
                ▼
              Kafka
```

This avoids the problematic sequence:

```text
❌ Database commit
        │
        X
   Kafka publish fails
```

or:

```text
❌ Kafka publish succeeds
        │
        X
   Database transaction rolls back
```

The event is first persisted as part of the same database transaction and is then relayed to Kafka.

---

# 10. Event Ownership

Each domain service owns the events associated with its domain.

```text
┌────────────────────────┐
│     Domain Service     │
├────────────────────────┤
│                        │
│ Owns data              │
│ Owns transaction       │
│ Owns domain events     │
│ Owns event production  │
│                        │
└───────────┬────────────┘
            │
            ▼
       Transactional
          Outbox
            │
            ▼
          Kafka
            │
     ┌──────┼──────┐
     ▼      ▼      ▼
 Consumer Consumer Consumer
```

Consumers should depend on the **event contract**, not on the producer's database schema.

This maintains service ownership and avoids shared-database coupling.

---

# 11. RabbitMQ Boundary

RabbitMQ is intentionally **not a second shared event backbone**.

```text
                     CROSS-SERVICE
                       EVENTS

appointment ─────┐
                 │
emr ─────────────┼────► Kafka
                 │
patient ─────────┤
                 │
doctor ──────────┘
                       │
                       ▼
                notification-service
                       │
                       ▼
                   RabbitMQ
                       │
                ┌──────┴──────┐
                ▼             ▼
             Delivery       Retry
                              │
                              ▼
                             DLQ
```

Only `notification-service` owns the RabbitMQ workflow.

Therefore:

```text
Kafka
 │
 ├──► billing-service
 ├──► audit-service
 ├──► analytics-service
 └──► notification-service
                          │
                          ▼
                     RabbitMQ
```

`billing-service`, `audit-service`, and `analytics-service` do not require RabbitMQ.

See [Communication Architecture](04-communication-architecture.md#11-rabbitmq-boundary).

---

# 12. Delivery Semantics

The event pipeline is designed around **at-least-once delivery**.

```text
Producer
   │
   ▼
Outbox
   │
   ▼
Relay
   │
   ▼
Kafka
   │
   ▼
Consumer
   │
   ├──── Success ────► Processed
   │
   └──── Failure ────► Retry / Reprocess
```

Because an event can potentially be delivered more than once:

```text
Event
  │
  ├──► Consumer
  │
  └──► Consumer again
```

Consumers should therefore be **idempotent** where the operation requires it.

The transactional outbox provides reliable handoff from the local database transaction to the relay, but it should not be interpreted as a global exactly-once guarantee.

---

# 13. Known Gaps

### Kafka observability

The platform does not yet provide comprehensive Kafka consumer-lag monitoring.

```text
Kafka
  │
  ▼
Consumer
  │
  ▼
Processing

   ? Consumer Lag
   ? Partition Health
   ? Processing Delay
```

These operational capabilities remain part of the platform's production-readiness roadmap.

### Kafka dead-letter handling

RabbitMQ has retry/DLQ handling for notification delivery, but Kafka does not currently have an equivalent platform-wide dead-letter topic strategy.

```text
Kafka
  │
  ▼
Consumer
  │
  X
Failure
  │
  └──► No standardized Kafka DLQ yet
```

### `audit.events`

`audit.events` is declared in the shared `Topics` definition but is currently unused.

```text
audit.events

Producer:  none
Consumer:  none

Status: declared / unused
```

It remains documented for completeness rather than being presented as an active event flow.

### Identity events

`identity-service` does not currently participate in Kafka.

```text
identity-service
      │
      ├── Kafka producer?  No
      └── Kafka consumer?  No
```

Authentication and RBAC remain request/response responsibilities of `identity-service`.

---

# 14. Related Documentation

| Document                                                                               | Focus                                               |
| -------------------------------------------------------------------------------------- | --------------------------------------------------- |
| [System Context](01-system-context.md)                                                 | External actors and systems                         |
| [Container Architecture](02-container-architecture.md)                                 | Runtime containers                                  |
| [Service Architecture](03-service-architecture.md)                                     | Service responsibilities and domain boundaries      |
| [Communication Architecture](04-communication-architecture.md)                         | REST, GraphQL, gRPC, Kafka, RabbitMQ, SOAP          |
| **Event Topology**                                                                     | Kafka topics, producers, consumers, and event flows |
| [Appointment Booking Sequence](10-appointment-booking-sequence.md)                     | Detailed booking workflow                           |
| [ADR-0002 — Messaging Topology](../adr/0002-messaging-topology.md)                     | Kafka/RabbitMQ architecture decision                |
| [ADR-0003 — SOAP Payer Eligibility](../adr/0003-soap-payer-eligibility-integration.md) | External payer integration                          |
| [ADR-0004 — gRPC](../adr/0004-grpc-for-internal-synchronous-calls.md)                  | Internal synchronous communication                  |

> **Event Architecture:** Domain services own their data and publish typed domain events through a transactional outbox into Kafka. Kafka provides the cross-service event backbone, while RabbitMQ remains deliberately scoped to notification delivery, retry, and DLQ processing. This keeps business workflows decoupled while preserving clear ownership and failure boundaries.
