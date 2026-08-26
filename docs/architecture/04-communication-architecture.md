# Communication Architecture

The platform deliberately uses **multiple communication patterns**, with each mechanism selected according to the interaction's requirements rather than forcing every workflow through a single protocol.

This document provides the detailed communication model. The [README Communication Model](../../README.md#communication-model) contains the concise project-level summary.

---

## Table of Contents

1. [Communication Overview](#1-communication-overview)
2. [Communication Styles](#2-communication-styles)
3. [External Request Flow](#3-external-request-flow)
4. [Internal Synchronous Communication](#4-internal-synchronous-communication)
5. [gRPC Contract Architecture](#5-grpc-contract-architecture)
6. [Event-Driven Communication](#6-event-driven-communication)
7. [Notification Messaging](#7-notification-messaging)
8. [Internal REST Communication](#8-internal-rest-communication)
9. [External SOAP Integration](#9-external-soap-integration)
10. [Why Each Protocol Exists](#10-why-each-protocol-exists)
11. [Failure Isolation](#11-failure-isolation)
12. [Communication Decision Matrix](#12-communication-decision-matrix)
13. [Architecture Principles](#13-architecture-principles)
14. [Related Architecture Documentation](#14-related-architecture-documentation)

---

## 1. Communication Overview

The platform separates communication into four major layers:

```text
╔══════════════════════════════════════════════════════════════════════╗
║                        CLIENT COMMUNICATION                          ║
║                                                                      ║
║              REST / GraphQL over HTTPS + JWT                        ║
╚═══════════════════════════════╤══════════════════════════════════════╝
                                │
                                ▼
                     ┌──────────────────────┐
                     │    NGINX Ingress     │
                     │   External Gateway   │
                     └──────────┬───────────┘
                                │
                                ▼
╔══════════════════════════════════════════════════════════════════════╗
║                     SYNCHRONOUS SERVICE CALLS                       ║
║                                                                      ║
║              gRPC                    Internal REST                  ║
║        Narrow contracts          Full-record / lower-volume         ║
╚══════════════════════════════╤═══════════════════════════════════════╝
                               │
                               ▼
╔══════════════════════════════════════════════════════════════════════╗
║                    ASYNCHRONOUS COMMUNICATION                       ║
║                                                                      ║
║                         Kafka                                        ║
║                  Domain Event Backbone                               ║
╚══════════════════════════════╤═══════════════════════════════════════╝
                               │
             ┌─────────────────┼─────────────────┐
             ▼                 ▼                 ▼
        Billing           Notification          Audit
        Service              Service           Service
                              │
                              ▼
                         ┌──────────┐
                         │ RabbitMQ │
                         │ Retry/DLQ│
                         └──────────┘

External trading-partner integration:
billing-service ───── SOAP ─────► External Payer
```

The key distinction is:

```text
                    COMMUNICATION MODEL

              ┌─────────────────────────────┐
              │       NEED AN ANSWER?       │
              └──────────────┬──────────────┘
                             │
                    ┌────────┴────────┐
                    │                 │
                   YES               NO
                    │                 │
                    ▼                 ▼
              Synchronous        Asynchronous
                    │                 │
              ┌─────┴─────┐           │
              ▼           ▼           ▼
            gRPC         REST       Kafka
                                    │
                                    ▼
                              RabbitMQ
                           (notification only)
```

---

## 2. Communication Styles

The platform uses the following communication mechanisms:

| Style    | Purpose                                          | Direction          | Example                        |
| -------- | ------------------------------------------------ | ------------------ | ------------------------------ |
| REST     | External and selected internal synchronous calls | Request / response | Frontend → platform            |
| GraphQL  | Client-facing API aggregation                    | Request / response | Frontend → GraphQL BFF         |
| gRPC     | Narrow internal synchronous contracts            | Request / response | Appointment → Patient          |
| Kafka    | Cross-service domain events                      | Asynchronous       | Appointment → Billing          |
| RabbitMQ | Notification task processing                     | Asynchronous       | Notification → delivery worker |
| SOAP     | External payer integration                       | Request / response | Billing → Payer                |

No single mechanism is treated as the universal communication layer.

---

## 3. External Request Flow

All client traffic enters through the platform's external boundary.

```text
┌─────────────────────┐
│    Client Browser   │
└──────────┬──────────┘
           │
           │ HTTPS
           │ JWT
           ▼
┌─────────────────────┐
│    Frontend SPA     │
└──────────┬──────────┘
           │
           │ REST / GraphQL
           ▼
┌─────────────────────┐
│    NGINX Ingress    │
│                     │
│ Single Entry Point  │
└──────────┬──────────┘
           │
     ┌─────┴──────────────────────────────┐
     │                                    │
     ▼                                    ▼
┌──────────────────┐             ┌──────────────────┐
│ REST Endpoints   │             │ GraphQL Gateway  │
│                  │             │      BFF         │
└────────┬─────────┘             └────────┬─────────┘
         │                                │
         ▼                                ▼
   Domain Services                 Domain Services
```

The external client does not need to know which internal service owns a particular capability.

This keeps the internal service topology behind the ingress boundary.

---

## 4. Internal Synchronous Communication

Synchronous communication is used when a service cannot continue correctly without an immediate response.

```text
                 INTERNAL SYNCHRONOUS CALLS

                         ┌───────────────────┐
                         │ Calling Service   │
                         └─────────┬─────────┘
                                   │
                         ┌─────────┴─────────┐
                         │                   │
                         ▼                   ▼
                       gRPC                REST
                         │                   │
                         ▼                   ▼
                ┌────────────────┐   ┌────────────────┐
                │ Narrow         │   │ Full / broader │
                │ contract       │   │ resource read  │
                └───────┬────────┘   └───────┬────────┘
                        │                    │
                        ▼                    ▼
                ┌────────────────┐   ┌────────────────┐
                │ Internal       │   │ Internal       │
                │ Service        │   │ Service        │
                └────────────────┘   └────────────────┘
```

### Appointment validation

Before creating an appointment, `appointment-service` validates the referenced patient and doctor synchronously.

```text
┌────────────────────────┐
│ appointment-service    │
└───────────┬────────────┘
            │
      ┌─────┴─────┐
      │           │
      │ gRPC      │ gRPC
      ▼           ▼
┌────────────┐ ┌────────────┐
│  Patient   │ │   Doctor   │
│  Lookup    │ │   Lookup   │
└─────┬──────┘ └─────┬──────┘
      │              │
      ▼              ▼
   Exists?        Exists?
      │              │
      └──────┬───────┘
             ▼
       Create Booking
```

The purpose is not to retrieve a complete patient or doctor record. It is to perform a small validation operation and fail the booking quickly when the referenced entity does not exist.

---

## 5. gRPC Contract Architecture

The gRPC layer intentionally uses **purpose-built contracts** instead of one broad service API.

```text
                         gRPC CONTRACTS

                     ┌────────────────────┐
                     │ appointment-service│
                     └─────────┬──────────┘
                               │
                   ┌───────────┴───────────┐
                   │                       │
                   │ PatientLookup         │ DoctorLookup
                   ▼                       ▼
          ┌──────────────────┐    ┌──────────────────┐
          │ patient-service  │    │  doctor-service  │
          │                  │    │                  │
          │ Narrow validation│    │ Narrow validation│
          └──────────────────┘    └──────────────────┘


                     ┌────────────────────┐
                     │ graphql-gateway    │
                     │       BFF          │
                     └─────────┬──────────┘
                               │
                   ┌───────────┴───────────┐
                   │                       │
                   │ PatientDirectory      │ DoctorDirectory
                   ▼                       ▼
          ┌──────────────────┐    ┌──────────────────┐
          │ patient-service  │    │  doctor-service  │
          │ Full read model  │    │ Full read model  │
          └──────────────────┘    └──────────────────┘
```

### Purpose-built contracts

There are two different classes of gRPC contracts:

**Lookup contracts**

```text
PatientLookup
DoctorLookup

Purpose:
"Does this entity exist?"
```

Used by `appointment-service`.

**Directory contracts**

```text
PatientDirectory
DoctorDirectory

Purpose:
"Give the gateway the data required to resolve GraphQL fields."
```

Used by `graphql-gateway`.

This separation keeps the contracts narrow and aligned with their callers.

### Lookup characteristics

The `PatientLookup` and `DoctorLookup` operations are:

* Unary.
* Internal.
* Purpose-built.
* Designed for fast validation.
* Protected by a short deadline.
* Not intended as general-purpose data APIs.

See [ADR-0004 — gRPC for Internal Synchronous Calls](../adr/0004-grpc-for-internal-synchronous-calls.md).

---

## 6. Event-Driven Communication

Kafka is the platform's **cross-service event backbone**.

```text
                    DOMAIN SERVICE
                          │
                          │
                          │ Publish Event
                          ▼
                 ╔══════════════════╗
                 ║      KAFKA       ║
                 ║                  ║
                 ║ Domain Events    ║
                 ╚════════╤═════════╝
                          │
              ┌───────────┼───────────┬──────────────┐
              │           │           │              │
              ▼           ▼           ▼              ▼
          Billing    Notification    Audit        Analytics
          Service       Service      Service        Service
```

For example:

```text
appointment-service
        │
        │ AppointmentCreated
        ▼
      Kafka
        │
        ├──────────────► billing-service
        │
        ├──────────────► notification-service
        │
        ├──────────────► audit-service
        │
        └──────────────► analytics-service
```

The publisher does not need to synchronously call every downstream service.

Instead:

```text
WITHOUT EVENTS

Appointment
    │
    ├──► Billing
    ├──► Notification
    ├──► Audit
    └──► Analytics


WITH EVENTS

Appointment
    │
    ▼
  Kafka
    │
    ├──► Billing
    ├──► Notification
    ├──► Audit
    └──► Analytics
```

This reduces direct coupling between domain services and allows downstream consumers to evolve independently.

See [Event Topology](05-event-topology.md).

---

## 7. Notification Messaging

RabbitMQ has a deliberately narrower role than Kafka.

```text
                         Kafka
                           │
                           │ Domain Event
                           ▼
                 ┌──────────────────────┐
                 │ notification-service │
                 └──────────┬───────────┘
                            │
                            │ Task
                            ▼
                     ╔══════════════╗
                     ║  RabbitMQ    ║
                     ║              ║
                     ║ Task Queue   ║
                     ╚══════╤═══════╝
                            │
                     ┌──────┴──────┐
                     │             │
                     ▼             ▼
                 Processing      Retry
                     │             │
                     │             ▼
                     │          ┌─────┐
                     │          │ DLQ │
                     │          └─────┘
                     ▼
              Notification
                Handler
                     │
          ┌──────────┼──────────┐
          ▼          ▼          ▼
        Email       SMS        Push
```

RabbitMQ is therefore **not another platform-wide event bus**.

Its responsibility is limited to notification processing, particularly:

* Delivery tasks.
* Retry handling.
* Dead-letter handling.
* Channel-specific processing.

---

## 8. Internal REST Communication

Not every internal call requires gRPC.

REST remains appropriate when the operation requires a broader resource representation, existing REST semantics, or does not justify a dedicated gRPC contract.

### GraphQL Gateway

```text
┌──────────────────────┐
│  graphql-gateway     │
└──────────┬───────────┘
           │
      ┌────┴─────┐
      │          │
     REST       REST
      │          │
      ▼          ▼
┌────────────┐ ┌────────────┐
│ Appointment│ │  Billing   │
│  Service   │ │  Service   │
└────────────┘ └────────────┘
```

### EMR referral workflow

```text
┌────────────────────┐
│    emr-service     │
└─────────┬──────────┘
          │
          │ REST
          │ Full patient demographics
          ▼
┌────────────────────┐
│  patient-service   │
└────────────────────┘
```

The EMR use case intentionally remains REST because it needs role-aware access to the patient resource rather than the narrow internal gRPC lookup contract.

---

## 9. External SOAP Integration

The payer integration is the platform's external SOAP boundary.

```text
┌──────────────────────┐
│ Authorized Client    │
└──────────┬───────────┘
           │
           │ REST
           ▼
┌──────────────────────┐
│   billing-service    │
└──────────┬───────────┘
           │
           │ SOAP
           │ Eligibility Request
           ▼
┌─────────────────────────────┐
│ External Payer /             │
│ Clearinghouse               │
└─────────────┬───────────────┘
              │
              │ Eligibility Response
              ▼
┌──────────────────────┐
│   billing-service    │
└──────────┬───────────┘
           │
           │ REST Response
           ▼
┌──────────────────────┐
│ Authorized Client    │
└──────────────────────┘
```

The SOAP protocol is imposed by the external trading-partner boundary.

It is not used as an internal platform communication mechanism.

Eligibility verification is intentionally synchronous and is not placed on the Kafka invoice event path.

See [ADR-0003 — SOAP Payer Eligibility Integration](../adr/0003-soap-payer-eligibility-integration.md).

---

## 10. Why Each Protocol Exists

```text
┌────────────────────────────────────────────────────────────────────┐
│                    COMMUNICATION DECISIONS                         │
├───────────────┬────────────────────────────────────────────────────┤
│ REST          │ External APIs and selected internal calls          │
│               │ Familiar HTTP semantics and resource-oriented APIs │
├───────────────┼────────────────────────────────────────────────────┤
│ GraphQL       │ Client-facing aggregation                          │
│               │ Reduces client-side service orchestration           │
├───────────────┼────────────────────────────────────────────────────┤
│ gRPC          │ Narrow internal synchronous contracts              │
│               │ Strong contracts and efficient service calls       │
├───────────────┼────────────────────────────────────────────────────┤
│ Kafka         │ Cross-service domain events                        │
│               │ Decoupled asynchronous processing                  │
├───────────────┼────────────────────────────────────────────────────┤
│ RabbitMQ      │ Notification tasks and retry/DLQ                   │
│               │ Operational delivery semantics                     │
├───────────────┼────────────────────────────────────────────────────┤
│ SOAP          │ External payer integration                         │
│               │ Required by external trading partner              │
└───────────────┴────────────────────────────────────────────────────┘
```

The architecture is therefore **protocol-by-requirement**, not protocol-by-preference.

---

## 11. Failure Isolation

Communication boundaries are also failure boundaries.

### Synchronous failure

```text
appointment-service
        │
        │ gRPC
        ▼
patient-service
        │
        X
   unavailable
        │
        ▼
Booking rejected quickly
```

A validation dependency failing should prevent an invalid booking rather than allowing inconsistent data.

### Asynchronous failure

```text
appointment-service
        │
        │ AppointmentCreated
        ▼
      Kafka
        │
        ├──────────► Billing
        │
        ├──────────► Notification ───► RabbitMQ
        │
        ├──────────► Audit
        │
        └──────────► Analytics
```

A downstream consumer can fail independently without blocking the original appointment request.

### Notification failure

```text
Notification
     │
     ▼
 RabbitMQ
     │
     ▼
 Delivery
     │
     X
   Failed
     │
     ▼
   Retry
     │
     X
   Failed
     │
     ▼
    DLQ
```

This prevents temporary notification-provider failures from turning into failed business transactions.

---

## 12. Communication Decision Matrix

| Communication Style | Scope                        | Synchronous | Primary Use                         |
| ------------------- | ---------------------------- | ----------: | ----------------------------------- |
| REST                | External + selected internal |         Yes | Resource APIs                       |
| GraphQL             | External                     |         Yes | Client-facing aggregation           |
| gRPC                | Internal                     |         Yes | Narrow service-to-service contracts |
| Kafka               | Internal                     |          No | Domain events                       |
| RabbitMQ            | Internal to notification     |          No | Tasks, retry, DLQ                   |
| SOAP                | External                     |         Yes | Payer eligibility                   |

### Current call map

| Caller          | Target          | Protocol      | Reason                   |
| --------------- | --------------- | ------------- | ------------------------ |
| Frontend        | NGINX Ingress   | HTTPS         | External access          |
| Frontend        | GraphQL Gateway | GraphQL/HTTPS | Aggregated API           |
| Frontend        | REST endpoints  | REST/HTTPS    | Direct platform APIs     |
| GraphQL Gateway | Patient         | gRPC          | Patient directory        |
| GraphQL Gateway | Doctor          | gRPC          | Doctor directory         |
| GraphQL Gateway | Appointment     | REST          | Appointment operations   |
| GraphQL Gateway | Billing         | REST          | Billing operations       |
| Appointment     | Patient         | gRPC          | Entity validation        |
| Appointment     | Doctor          | gRPC          | Entity validation        |
| EMR             | Patient         | REST          | Full demographic read    |
| Appointment     | Kafka           | Event         | Appointment lifecycle    |
| EMR             | Kafka           | Event         | Clinical lifecycle       |
| Kafka           | Billing         | Event         | Billing reaction         |
| Kafka           | Notification    | Event         | Notification reaction    |
| Kafka           | Audit           | Event         | Audit processing         |
| Kafka           | Analytics       | Event         | Analytics processing     |
| Notification    | RabbitMQ        | Task          | Delivery / retry         |
| Billing         | Payer           | SOAP          | Eligibility verification |

---

## 13. Architecture Principles

### 13.1 Use Synchronous Communication When a Decision Is Required

If the caller cannot proceed without an answer, REST or gRPC is appropriate.

### 13.2 Use Events for Independent Reactions

If multiple services need to react to a business event without blocking the originating request, Kafka is preferred.

### 13.3 Keep gRPC Contracts Narrow

gRPC is not treated as a replacement for every REST API. Contracts are created around concrete internal use cases.

### 13.4 Keep RabbitMQ Scoped

RabbitMQ exists for notification task processing and retry/DLQ semantics. It is not a second shared event bus.

### 13.5 Keep External Protocols at the Boundary

SOAP exists because the payer requires it. External protocol requirements should not leak into the internal service communication model.

### 13.6 Design for Failure Isolation

Synchronous dependencies are kept narrow and fast, while asynchronous processing allows downstream capabilities to fail and recover independently.

### 13.7 Preserve Clear Ownership

Services communicate through explicit contracts and events without sharing internal persistence or implementation details.

---

## 14. Related Architecture Documentation

| Document                                                           | Focus                                            |
| ------------------------------------------------------------------ | ------------------------------------------------ |
| [System Context](01-system-context.md)                             | Users and external system boundaries             |
| [Container Architecture](02-container-architecture.md)             | Runtime containers                               |
| [Service Architecture](03-service-architecture.md)                 | Domain services and ownership                    |
| [Communication Architecture](04-communication-architecture.md)     | Communication protocols and interaction patterns |
| [Event Topology](05-event-topology.md)                             | Kafka topics and event flows                     |
| [Appointment Booking Sequence](10-appointment-booking-sequence.md) | Detailed synchronous + asynchronous booking flow |
| [ADR-0002](../adr/0002-messaging-topology.md)                      | Messaging topology                               |
| [ADR-0003](../adr/0003-soap-payer-eligibility-integration.md)      | SOAP payer integration                           |
| [ADR-0004](../adr/0004-grpc-for-internal-synchronous-calls.md)     | Internal gRPC decision                           |

> **Communication Architecture:** The platform intentionally combines **REST, GraphQL, gRPC, Kafka, RabbitMQ, and SOAP**, with each mechanism serving a distinct communication requirement. The goal is not protocol uniformity; it is **clear boundaries, appropriate coupling, and predictable failure behavior**.
