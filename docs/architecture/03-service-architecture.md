# Service Architecture

The **Service Architecture** is the domain-oriented view of the Healthcare Microservices Platform. It shows how the platform's services are organized by business responsibility, how they interact, and which components provide shared platform capabilities.

The platform contains **nine domain/supporting Spring Boot services plus a GraphQL BFF**, with each runtime service owning a focused responsibility and its own persistence boundary where applicable.

For ports and a concise service inventory, see the [Services table](../../README.md#services).

---

## Table of Contents

1. [Service Landscape](#1-service-landscape)
2. [Identity & Access](#2-identity--access)
3. [Clinical Domain](#3-clinical-domain)
4. [Scheduling](#4-scheduling)
5. [Financial](#5-financial)
6. [Supporting & Cross-Cutting Services](#6-supporting--cross-cutting-services)
7. [Edge & API Layer](#7-edge--api-layer)
8. [Service Communication Model](#8-service-communication-model)
9. [Shared Library Modules](#9-shared-library-modules)
10. [Service Ownership Model](#10-service-ownership-model)
11. [Architecture Principles](#11-architecture-principles)
12. [Related Architecture Documentation](#12-related-architecture-documentation)

---

## 1. Service Landscape

The services are organized by **business capability**, rather than presented as a flat list.

```text
╔══════════════════════════════════════════════════════════════════════════════╗
║                         HEALTHCARE PLATFORM SERVICES                         ║
╚══════════════════════════════════════════════════════════════════════════════╝


┌──────────────────────────────┐
│      EDGE / API LAYER        │
├──────────────────────────────┤
│                              │
│     graphql-gateway (BFF)    │
│                              │
│  Client-facing API           │
└───────────────┬──────────────┘
                │
                │ gRPC / REST
                ▼

┌──────────────────────────────┐
│      IDENTITY & ACCESS       │
├──────────────────────────────┤
│                              │
│      identity-service        │
│                              │
│ Authentication • JWT • RBAC  │
└──────────────────────────────┘


┌─────────────────────────────────────────────────────────────────────────────┐
│                            CLINICAL DOMAIN                                  │
├──────────────────────┬──────────────────────┬───────────────────────────────┤
│                      │                      │                               │
│   patient-service    │    doctor-service    │        emr-service             │
│                      │                      │                               │
│ Demographics         │ Profiles             │ Encounters                    │
│ Insurance            │ Departments         │ Diagnoses                     │
│ Medical history      │ Specialties         │ Medications                   │
│                      │ Availability         │ Labs / Allergies              │
│                      │                      │                               │
└──────────────────────┴──────────────────────┴───────────────────────────────┘


┌──────────────────────────────┐
│         SCHEDULING           │
├──────────────────────────────┤
│                              │
│    appointment-service       │
│                              │
│ Booking • Cancellation       │
│ Rescheduling • Availability  │
└──────────────────────────────┘


┌──────────────────────────────┐
│          FINANCIAL           │
├──────────────────────────────┤
│                              │
│       billing-service        │
│                              │
│ Invoices • Billing Events    │
│ Insurance Eligibility        │
└──────────────────────────────┘


┌─────────────────────────────────────────────────────────────────────────────┐
│                     SUPPORTING / CROSS-CUTTING                             │
├────────────────────────────┬────────────────────────┬───────────────────────┤
│                            │                        │                       │
│  notification-service      │    audit-service       │  analytics-service    │
│                            │                        │                       │
│  Notifications             │    Audit trail         │  Analytics            │
│  Retry / DLQ               │    Append-only log     │  Reporting            │
│  Email / SMS / Push        │                        │  Event processing     │
│                            │                        │                       │
└────────────────────────────┴────────────────────────┴───────────────────────┘
```

### Service map

```text
                    ┌──────────────────────┐
                    │  graphql-gateway      │
                    │       BFF             │
                    └──────────┬───────────┘
                               │
             ┌─────────────────┼──────────────────┐
             │                 │                  │
             ▼                 ▼                  ▼
       identity-service   Clinical Services   appointment-service
                              │
                       ┌──────┼──────┐
                       ▼      ▼      ▼
                    Patient Doctor  EMR
                                             │
                                             ▼
                                      billing-service
                                             │
                                             ▼
                                        External Payer


                         DOMAIN EVENTS
                              │
                              ▼
                         ┌─────────┐
                         │  Kafka  │
                         └────┬────┘
                              │
                ┌─────────────┼─────────────┐
                ▼             ▼             ▼
          Notification       Audit       Analytics
             Service        Service        Service
                │
                ▼
            RabbitMQ
```

---

## 2. Identity & Access

### `identity-service`

`identity-service` is the platform's **central identity and authorization authority**.

```text
┌─────────────────────────────┐
│      identity-service       │
├─────────────────────────────┤
│                             │
│ Registration                │
│ Authentication              │
│ JWT Access Tokens           │
│ Refresh Tokens               │
│ Logout                      │
│ Role-Based Access Control   │
│                             │
└──────────────┬──────────────┘
               │
               ▼
        ┌──────────────┐
        │ identity_db  │
        └──────────────┘
```

The service follows a request/response model and is intentionally isolated from the platform's event infrastructure.

```text
Client
  │
  │ REST
  ▼
identity-service
  │
  ▼
identity_db
```

It does **not** publish domain events and does not directly consume Kafka or RabbitMQ.

---

## 3. Clinical Domain

The Clinical Domain contains three independently deployable services.

```text
                    CLINICAL DOMAIN
                           │
          ┌────────────────┼────────────────┐
          │                │                │
          ▼                ▼                ▼
 ┌────────────────┐ ┌───────────────┐ ┌────────────────┐
 │ patient-service│ │ doctor-service │ │  emr-service   │
 ├────────────────┤ ├───────────────┤ ├────────────────┤
 │ Demographics    │ │ Profiles      │ │ Encounters     │
 │ Insurance       │ │ Departments   │ │ Diagnoses      │
 │ Medical history │ │ Specialties   │ │ Medications    │
 │ Patient data    │ │ Availability  │ │ Lab results    │
 └───────┬────────┘ └───────┬───────┘ │ Allergies      │
         │                  │          └───────┬────────┘
         ▼                  ▼                  │
   patient_db          doctor_db              │ REST
                                              ▼
                                       patient-service
```

### `patient-service`

Owns patient-facing demographic and insurance information.

```text
┌──────────────────────────┐
│    patient-service       │
├──────────────────────────┤
│ Demographics             │
│ Insurance                │
│ Medical history          │
│ Patient identity data    │
└─────────────┬────────────┘
              │
              ▼
       ┌─────────────┐
       │ patient_db  │
       └─────────────┘
```

It is accessed through:

* REST from the external API boundary.
* gRPC by `graphql-gateway`.
* gRPC by `appointment-service`.
* REST by `emr-service` for its referral workflow.

### `doctor-service`

Owns doctor and clinical staff information.

```text
┌──────────────────────────┐
│     doctor-service       │
├──────────────────────────┤
│ Doctor profiles          │
│ Departments              │
│ Specialties              │
│ Availability             │
└─────────────┬────────────┘
              │
              ▼
       ┌─────────────┐
       │  doctor_db  │
       └─────────────┘
```

It provides synchronous gRPC operations to services that need doctor validation or availability information.

### `emr-service`

Owns the clinical record workflows.

```text
┌──────────────────────────┐
│       emr-service        │
├──────────────────────────┤
│ Encounters               │
│ Diagnoses                │
│ Medications              │
│ Allergies                │
│ Lab results              │
│ Clinical documents       │
└─────────────┬────────────┘
              │
              ▼
         ┌─────────┐
         │  emr_db │
         └─────────┘
```

Its referral workflow communicates with `patient-service` over REST because it requires full patient demographic information rather than a simple existence check.

Clinical changes also generate events for downstream processing.

```text
emr-service
     │
     │ Domain Events
     ▼
   Kafka
     │
     ├──────────► notification-service
     ├──────────► audit-service
     └──────────► analytics-service
```

---

## 4. Scheduling

### `appointment-service`

`appointment-service` is the platform's central scheduling service and one of its most connected domain services.

```text
                         ┌─────────────────────┐
                         │ appointment-service  │
                         └──────────┬──────────┘
                                    │
                  ┌─────────────────┼─────────────────┐
                  │                 │                 │
                  │ gRPC            │ gRPC            │
                  ▼                 ▼                 │
          ┌───────────────┐ ┌───────────────┐         │
          │    Patient    │ │    Doctor     │         │
          │    Service    │ │    Service    │         │
          └───────────────┘ └───────────────┘         │
                                                      │
                                                      │ Event
                                                      ▼
                                                  ┌─────────┐
                                                  │  Kafka  │
                                                  └────┬────┘
                                                       │
                                         ┌─────────────┼─────────────┐
                                         ▼             ▼             ▼
                                      Billing     Notification     Audit
                                                    Analytics
```

The booking workflow:

1. Receives the appointment request.
2. Validates patient information.
3. Validates doctor availability.
4. Persists the appointment.
5. Publishes the appointment event.
6. Allows downstream services to react independently.

See [Appointment Booking Sequence](10-appointment-booking-sequence.md).

---

## 5. Financial

### `billing-service`

`billing-service` owns billing workflows and reacts to appointment lifecycle events.

```text
                  ┌──────────────────────┐
                  │    Kafka Events      │
                  └──────────┬───────────┘
                             │
                             ▼
                    ┌─────────────────┐
                    │ billing-service │
                    └────────┬────────┘
                             │
                  ┌──────────┴──────────┐
                  │                     │
                  ▼                     ▼
           ┌──────────────┐    ┌────────────────────┐
           │  billing_db  │    │ External Payer     │
           │              │    │ / Clearinghouse    │
           └──────────────┘    └────────────────────┘
                                      ▲
                                      │ SOAP
                                      │
                              Eligibility Check
```

Responsibilities include:

* Invoice generation.
* Billing lifecycle events.
* Appointment-related billing reactions.
* Insurance eligibility verification.

The external payer integration is deliberately synchronous and isolated from the asynchronous invoice event flow.

See [System Context](01-system-context.md).

---

## 6. Supporting & Cross-Cutting Services

The supporting services consume platform events rather than becoming direct dependencies of every domain service.

```text
                    ┌─────────────────┐
                    │      Kafka      │
                    └────────┬────────┘
                             │
            ┌────────────────┼────────────────┐
            │                │                │
            ▼                ▼                ▼
   ┌────────────────┐ ┌──────────────┐ ┌────────────────┐
   │ notification   │ │    audit     │ │   analytics    │
   │    service     │ │   service    │ │    service     │
   └───────┬────────┘ └──────────────┘ └────────────────┘
           │
           ▼
      ┌──────────┐
      │ RabbitMQ │
      └──────────┘
```

### `notification-service`

Responsible for asynchronous notification processing.

```text
Kafka
  │
  ▼
notification-service
  │
  ▼
RabbitMQ
  │
  ├──────► Notification Handler
  │
  └──────► Retry / Dead Letter Queue
```

The service supports channel-specific processing for email, SMS, and push notifications.

Actual external delivery is currently simulated/logged.

### `audit-service`

Consumes platform events and maintains an append-oriented audit trail.

```text
Domain Services
       │
       ▼
     Kafka
       │
       ▼
 audit-service
       │
       ▼
 Append-only Audit Log
```

The service is not called directly by frontend clients.

### `analytics-service`

Consumes domain events and performs event-driven aggregation and scheduled reporting.

```text
Domain Events
     │
     ▼
   Kafka
     │
     ▼
analytics-service
     │
     ├──────► Event Counts
     │
     └──────► Scheduled Reports
                    │
                    ▼
                  Quartz
```

---

## 7. Edge & API Layer

### `graphql-gateway`

The GraphQL gateway is a **stateless Backend-for-Frontend (BFF)**.

It does not own business data.

```text
                         ┌─────────────────────┐
                         │   Client / SPA      │
                         └──────────┬──────────┘
                                    │
                                  GraphQL
                                    │
                                    ▼
                         ┌─────────────────────┐
                         │  graphql-gateway    │
                         │       BFF           │
                         └──────────┬──────────┘
                                    │
                  ┌─────────────────┼─────────────────┐
                  │                 │                 │
                gRPC              gRPC              REST
                  │                 │                 │
                  ▼                 ▼                 ▼
             patient-service   doctor-service   appointment-service
                                                        │
                                                        │
                                                        ▼
                                                  billing-service
```

The gateway aggregates data from the owning services rather than introducing a second persistence layer.

### Gateway responsibilities

* GraphQL API.
* Request aggregation.
* Field resolution.
* Communication with domain services.
* Client-oriented API composition.

### Gateway does not own

* A database.
* Domain persistence.
* Business event ownership.
* Long-running background processing.

---

## 8. Service Communication Model

The platform uses different communication mechanisms for different interaction patterns.

```text
                    SERVICE COMMUNICATION
                            │
              ┌─────────────┴─────────────┐
              │                           │
              ▼                           ▼
       SYNCHRONOUS                   ASYNCHRONOUS
              │                           │
       ┌──────┴──────┐             ┌──────┴──────┐
       │             │             │             │
      REST          gRPC          Kafka       RabbitMQ
       │             │             │             │
       ▼             ▼             ▼             ▼
   Immediate      Internal      Domain        Tasks /
    request       service       events        retry / DLQ
    /response     calls
```

### Synchronous

| From          | To          | Protocol   | Purpose                       |
| ------------- | ----------- | ---------- | ----------------------------- |
| Browser / SPA | Ingress     | HTTP/HTTPS | External access               |
| Ingress       | Services    | REST       | API routing                   |
| GraphQL BFF   | Patient     | gRPC       | Patient queries               |
| GraphQL BFF   | Doctor      | gRPC       | Doctor queries                |
| GraphQL BFF   | Appointment | REST       | Appointment operations        |
| GraphQL BFF   | Billing     | REST       | Billing operations            |
| Appointment   | Patient     | gRPC       | Patient validation            |
| Appointment   | Doctor      | gRPC       | Availability validation       |
| EMR           | Patient     | REST       | Referral / demographic lookup |
| Billing       | Payer       | SOAP       | Eligibility verification      |

### Asynchronous

```text
                  ┌──────────────────┐
                  │   Domain Event   │
                  └────────┬─────────┘
                           │
                           ▼
                     ┌───────────┐
                     │   Kafka   │
                     └─────┬─────┘
                           │
              ┌────────────┼─────────────┐
              ▼            ▼             ▼
          Billing     Notification      Audit
                                         │
                                         ▼
                                     Analytics
```

This separation prevents asynchronous consumers from becoming tightly coupled to the request path.

---

## 9. Shared Library Modules

Two modules support the services at build time but are **not runtime containers**.

```text
                         SOURCE CODE
                              │
               ┌──────────────┴──────────────┐
               │                             │
               ▼                             ▼
          ┌─────────┐                 ┌───────────────┐
          │ common  │                 │ grpc-contracts│
          └────┬────┘                 └───────┬───────┘
               │                              │
               │                              │
               └──────────────┬───────────────┘
                              ▼
                    ┌─────────────────────┐
                    │ Service JARs        │
                    │                     │
                    │ Spring Boot services│
                    └─────────────────────┘
```

### `common`

Contains reusable platform components such as:

* Domain events.
* DTOs.
* Exceptions.
* JWT verification.
* Transactional outbox implementation.
* `OutboxEvent`.
* `OutboxWriter`.
* `OutboxRelay`.

It is compiled into service JARs and is not deployed independently.

### `grpc-contracts`

Contains generated gRPC classes from the `.proto` definitions.

```text
.proto definitions
       │
       ▼
┌──────────────────┐
│ gRPC codegen     │
└────────┬─────────┘
         ▼
┌──────────────────┐
│ grpc-contracts   │
│                  │
│ Messages         │
│ Stubs            │
└────────┬─────────┘
         │
         ▼
Service JARs
```

It is deliberately separated from `common` so lightweight consumers such as `graphql-gateway` can depend on the wire contracts without inheriting the persistence, security, and web dependencies contained in `common`.

See [ADR-0004 — gRPC for Internal Synchronous Calls](../adr/0004-grpc-for-internal-synchronous-calls.md).

---

## 10. Service Ownership Model

The platform follows **business-capability ownership**.

```text
┌─────────────────────┐
│ Business Capability │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│ Owning Service      │
└──────────┬──────────┘
           │
     ┌─────┴─────┐
     ▼           ▼
  Own Data    Own Events
```

| Domain            | Owning Service         | Primary Data                         |
| ----------------- | ---------------------- | ------------------------------------ |
| Identity & Access | `identity-service`     | Identity / credentials / roles       |
| Patients          | `patient-service`      | Patient demographics / insurance     |
| Doctors           | `doctor-service`       | Doctor / department / specialty      |
| Scheduling        | `appointment-service`  | Appointments                         |
| Clinical Records  | `emr-service`          | Encounters / diagnoses / medications |
| Billing           | `billing-service`      | Invoices / billing data              |
| Notifications     | `notification-service` | Notification processing              |
| Audit             | `audit-service`        | Audit events                         |
| Analytics         | `analytics-service`    | Analytical projections / reporting   |
| API Composition   | `graphql-gateway`      | No persistent domain data            |

This keeps domain ownership explicit and reduces shared-state coupling.

---

## 11. Architecture Principles

### 11.1 Business Capability Boundaries

Each service represents a focused business capability rather than a technical layer.

### 11.2 Independent Deployment

Services are independently buildable and deployable Spring Boot applications.

### 11.3 Data Ownership

Domain services own their persistence and are responsible for changes to their data.

### 11.4 Synchronous Where Required

REST and gRPC are used where the caller requires an immediate result.

### 11.5 Events for Decoupling

Kafka allows downstream capabilities such as billing, notifications, auditing, and analytics to react independently to domain changes.

### 11.6 Stateless API Composition

The GraphQL gateway aggregates data but does not become another source of truth.

### 11.7 Internal Services Are Not Automatically Public

Services are exposed only where there is a clear client-facing requirement. Backend consumers can remain internal and communicate through REST, gRPC, Kafka, or RabbitMQ.

### 11.8 Shared Code Is Not a Service

`common` and `grpc-contracts` provide build-time reuse without introducing unnecessary runtime deployment units.

---

## 12. Related Architecture Documentation

| Document                                                           | Focus                                        |
| ------------------------------------------------------------------ | -------------------------------------------- |
| [System Context](01-system-context.md)                             | Users, system boundary, and external systems |
| [Container Architecture](02-container-architecture.md)             | Deployable runtime containers                |
| [Service Architecture](03-service-architecture.md)                 | Domain services and ownership                |
| [Data Architecture](03-data-architecture.md)                       | Databases and data ownership                 |
| [Communication Architecture](04-communication-architecture.md)     | REST, gRPC, Kafka, and RabbitMQ              |
| [Event Topology](05-event-topology.md)                             | Kafka events and topic relationships         |
| [Appointment Booking Sequence](10-appointment-booking-sequence.md) | End-to-end appointment workflow              |
| [ADR-0001](../adr/0001-api-gateway-ingress.md)                     | API gateway / ingress decision               |
| [ADR-0004](../adr/0004-grpc-for-internal-synchronous-calls.md)     | Internal gRPC decision                       |

> **C4 Level 2/Domain view:** This document explains **which services own which business capabilities and how they collaborate**. The Container Architecture focuses on runtime deployment units, while lower-level service documentation covers implementation details, APIs, persistence models, and workflows.
