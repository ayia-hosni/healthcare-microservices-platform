# Data Architecture

The platform follows a **database-per-service** model combined with **polyglot persistence**. Each stateful service owns its data, while Kafka events and synchronous APIs provide the boundaries between services.

---

## Table of Contents

1. [Data Architecture Overview](#1-data-architecture-overview)
2. [Database-per-Service](#2-database-per-service)
3. [Data Ownership](#3-data-ownership)
4. [Polyglot Persistence](#4-polyglot-persistence)
5. [Storage Responsibilities](#5-storage-responsibilities)
6. [Cross-Service Data Access](#6-cross-service-data-access)
7. [Appointment Booking Data Flow](#7-appointment-booking-data-flow)
8. [Transactional Consistency](#8-transactional-consistency)
9. [Caching Strategy](#9-caching-strategy)
10. [Document & Object Storage](#10-document--object-storage)
11. [Elasticsearch Status](#11-elasticsearch-status)
12. [Data Architecture Principles](#12-data-architecture-principles)
13. [Related Documentation](#13-related-documentation)

---

# 1. Data Architecture Overview

```text
╔══════════════════════════════════════════════════════════════════════╗
║                         APPLICATION SERVICES                         ║
╚══════════════════════════════════════════════════════════════════════╝
        │             │             │             │
        │             │             │             │
        ▼             ▼             ▼             ▼
┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────┐
│ Identity   │  │ Patient    │  │ Doctor     │  │ Appointment│
│ Service    │  │ Service    │  │ Service    │  │ Service    │
└─────┬──────┘  └─────┬──────┘  └─────┬──────┘  └─────┬──────┘
      │               │               │               │
      ▼               ▼               ▼               ▼
 identity_db     patient_db       doctor_db      appointment_db


┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────┐
│ EMR        │  │ Billing    │  │ Notification│ │ Audit      │
│ Service    │  │ Service    │  │ Service     │ │ Service    │
└─────┬──────┘  └─────┬──────┘  └─────┬───────┘ └─────┬──────┘
      │               │               │               │
      ▼               ▼               ▼               ▼
   emr_db         billing_db    notification_db    audit_db


                    ┌──────────────────────┐
                    │ Analytics Service    │
                    └──────────┬───────────┘
                               ▼
                         analytics_db
```

The central rule is:

```text
┌──────────────────────────────────────────────────────────┐
│                 SERVICE DATA OWNERSHIP                    │
├──────────────────────────────────────────────────────────┤
│                                                          │
│  Service A ────────► Service A Database                  │
│                                                          │
│  Service B ────────► Service B Database                  │
│                                                          │
│  Service C ────────► Service C Database                  │
│                                                          │
│  ❌ Service A ─────► Service B Database                   │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

No service directly reads or writes another service's tables.

---

# 2. Database-per-Service

Every stateful service owns a logically isolated PostgreSQL database.

```text
                         PostgreSQL
                              │
       ┌──────────────────────┼───────────────────────┐
       │                      │                       │
       ▼                      ▼                       ▼
┌─────────────┐        ┌─────────────┐        ┌─────────────┐
│ identity_db │        │ patient_db  │        │ doctor_db   │
└──────┬──────┘        └──────┬──────┘        └──────┬──────┘
       ▲                      ▲                       ▲
       │                      │                       │
 identity-service       patient-service        doctor-service


       ┌──────────────────────┼───────────────────────┐
       │                      │                       │
       ▼                      ▼                       ▼
┌───────────────┐      ┌─────────────┐        ┌─────────────┐
│ appointment_db│      │   emr_db     │        │ billing_db  │
└───────┬───────┘      └──────┬──────┘        └──────┬──────┘
        ▲                     ▲                       ▲
        │                     │                       │
 appointment-service     emr-service           billing-service


       ┌──────────────────────┼───────────────────────┐
       │                      │                       │
       ▼                      ▼                       ▼
┌────────────────┐     ┌─────────────┐        ┌───────────────┐
│ notification_db│     │  audit_db   │        │ analytics_db  │
└───────┬────────┘     └──────┬──────┘        └───────┬───────┘
        ▲                     ▲                       ▲
        │                     │                       │
 notification-service    audit-service          analytics-service
```

This provides clear ownership boundaries and prevents shared-schema coupling.

---

# 3. Data Ownership

Each service is responsible for the lifecycle of its own data.

```text
┌────────────────────────────────────────────────────────────┐
│                     DATA OWNERSHIP                         │
└────────────────────────────────────────────────────────────┘

Identity
   │
   └──► Users / Credentials / Roles / Authentication State

Patient
   │
   └──► Demographics / Insurance / Patient Profile

Doctor
   │
   └──► Doctor Profile / Departments / Specialties / Availability

Appointment
   │
   └──► Appointments / Booking State

EMR
   │
   └──► Encounters / Diagnoses / Medications / Allergies /
        Laboratory Results / Documents

Billing
   │
   └──► Invoices / Payments / Billing State

Notification
   │
   └──► Notification Requests / Delivery State / Retry State

Audit
   │
   └──► Audit Records / Archived Audit Data

Analytics
   │
   └──► Event-derived Reporting / Aggregated Analytics
```

The ownership model means that other services cannot bypass the owning service to obtain its authoritative data.

---

# 4. Polyglot Persistence

The platform does not force every workload into PostgreSQL.

Different technologies are used according to the workload.

```text
                         APPLICATION SERVICES
                                │
          ┌─────────────────────┼─────────────────────┐
          │                     │                     │
          ▼                     ▼                     ▼
     TRANSACTIONAL          FAST READS            OBJECTS
          │                     │                     │
          ▼                     ▼                     ▼
     PostgreSQL              Redis                 MinIO
          │
          │
          ├──────────────────────► Kafka
          │                         │
          │                         ▼
          │                  Domain Events
          │
          └──────────────────────► RabbitMQ
                                    │
                                    ▼
                              Notification Tasks
```

---

# 5. Storage Responsibilities

| Technology        | Consumers                           | Responsibility                          |
| ----------------- | ----------------------------------- | --------------------------------------- |
| **PostgreSQL**    | All stateful services               | Transactional service-owned data        |
| **Redis**         | `patient-service`, `doctor-service` | Read-path caching                       |
| **MinIO**         | `emr-service`, `audit-service`      | Documents and archived audit data       |
| **Kafka**         | Domain/event-producing services     | Durable cross-service event stream      |
| **RabbitMQ**      | `notification-service`              | Notification tasks, retry and DLQ       |
| **Elasticsearch** | None currently                      | Reserved for future search capabilities |

### PostgreSQL

PostgreSQL is the authoritative transactional store.

```text
Service
   │
   ▼
PostgreSQL
   │
   ├── Business data
   ├── Transaction state
   └── Outbox events
```

### Redis

Redis is used as a cache rather than the source of truth.

```text
Request
   │
   ▼
Service
   │
   ├──── Cache Hit ─────► Redis ─────► Response
   │
   └──── Cache Miss ────► PostgreSQL
                              │
                              ▼
                           Redis
                              │
                              ▼
                           Response
```

### Kafka

Kafka stores the cross-service event stream.

```text
Service
   │
   ▼
Outbox
   │
   ▼
Kafka
   │
   ├────► Billing
   ├────► Audit
   ├────► Analytics
   └────► Notification
```

### RabbitMQ

RabbitMQ is deliberately scoped to notification processing.

```text
notification-service
        │
        ▼
    RabbitMQ
        │
   ┌────┴─────┐
   ▼          ▼
Delivery    Retry
               │
               ▼
              DLQ
```

---

# 6. Cross-Service Data Access

Cross-service access happens through explicit service boundaries.

```text
                         SERVICE BOUNDARY

┌─────────────────────┐
│     Service A       │
│                     │
│       Data A        │
└──────────┬──────────┘
           │
           │
           ├──────── REST ────────────┐
           │                          │
           ├──────── gRPC ────────────┤
           │                          ▼
           └──────── Kafka ─────► ┌──────────────┐
                                  │   Service B  │
                                  │              │
                                  │    Data B    │
                                  └──────────────┘

                    ❌
           Direct database access
                    │
                    X
```

### Allowed

```text
Service A
   │
   ├──► REST
   ├──► gRPC
   └──► Kafka events
```

### Not allowed

```text
Service A ─────────X────────► Service B Database
```

This prevents:

* shared-schema coupling
* hidden dependencies
* uncontrolled cross-service writes
* database-level integration contracts
* difficult independent deployments

---

# 7. Appointment Booking Data Flow

Appointment creation demonstrates how local transactions become distributed data changes.

```text
                         BOOKING REQUEST
                               │
                               ▼
                    ┌────────────────────┐
                    │ appointment-service │
                    └─────────┬──────────┘
                              │
                              ▼
                    ┌────────────────────┐
                    │  appointment_db    │
                    │                    │
                    │ Appointment        │
                    │ OutboxEvent        │
                    └─────────┬──────────┘
                              │
                           COMMIT
                              │
                              ▼
                       Outbox Relay
                              │
                              ▼
                         ╔════════╗
                         ║ Kafka  ║
                         ╚═══╤════╝
                             │
             ┌───────────────┼────────────────┐
             │               │                │
             ▼               ▼                ▼
       billing-service  audit-service   analytics-service
             │               │                │
             ▼               ▼                ▼
        billing_db        audit_db       analytics_db
```

The important architectural property is that **each consumer writes only to its own database**.

```text
appointment_db
      │
      │ AppointmentCreatedEvent
      ▼
    Kafka
      │
      ├────► billing-service ─────► billing_db
      │
      ├────► audit-service ───────► audit_db
      │
      └────► analytics-service ───► analytics_db
```

No consumer reaches back into `appointment_db`.

---

# 8. Transactional Consistency

The platform uses the transactional outbox pattern to keep business state and event publication coordinated.

```text
                  ONE DATABASE TRANSACTION

┌────────────────────────────────────────────┐
│              Service Database             │
│                                            │
│  ┌──────────────────┐  ┌────────────────┐ │
│  │ Business Record  │  │ Outbox Event   │ │
│  │                  │  │                │ │
│  │ Appointment      │  │ Event Type     │ │
│  │ Invoice          │  │ Payload        │ │
│  │ Patient          │  │ Status         │ │
│  └────────┬─────────┘  └───────┬────────┘ │
│           │                    │          │
│           └──────────┬─────────┘          │
│                      │                    │
│                   COMMIT                  │
└──────────────────────┬─────────────────────┘
                       │
                       ▼
                 Outbox Relay
                       │
                       ▼
                     Kafka
```

This avoids coupling the business transaction directly to Kafka availability.

```text
Business Transaction
       │
       ├──► Business Data
       │
       └──► Outbox Event
               │
             COMMIT
               │
               ▼
          Kafka Relay
               │
               ▼
             Kafka
```

See [Event Topology](05-event-topology.md#9-transactional-outbox) for the complete event architecture.

---

# 9. Caching Strategy

Redis is used selectively for read-heavy paths.

```text
                         READ REQUEST
                              │
                              ▼
                     ┌─────────────────┐
                     │    Service      │
                     └────────┬────────┘
                              │
                              ▼
                        ┌───────────┐
                        │   Redis   │
                        └─────┬─────┘
                              │
                    ┌─────────┴─────────┐
                    │                   │
                  HIT                  MISS
                    │                   │
                    ▼                   ▼
                Response          PostgreSQL
                                        │
                                        ▼
                                      Redis
                                        │
                                        ▼
                                    Response
```

Current cache consumers:

```text
Redis
  │
  ├──► patient-service
  │
  └──► doctor-service
```

Redis is therefore an optimization layer, not an authoritative persistence layer.

---

# 10. Document & Object Storage

MinIO handles object/document-oriented data that does not belong directly inside transactional database rows.

```text
                       APPLICATION SERVICES
                               │
                  ┌────────────┴────────────┐
                  │                         │
                  ▼                         ▼
             emr-service              audit-service
                  │                         │
                  ▼                         ▼
             ┌─────────┐              ┌─────────┐
             │  MinIO  │              │  MinIO  │
             └────┬────┘              └────┬────┘
                  │                         │
                  ▼                         ▼
            EMR Documents             Audit Archives
```

Typical responsibilities:

* EMR document attachments
* Object storage for clinical documents
* Scheduled audit-log archival

The relational database remains responsible for the metadata and transactional state associated with these objects.

---

# 11. Elasticsearch Status

Elasticsearch is provisioned in the local Docker environment but is **not currently part of the active application data path**.

```text
                    CURRENT DATA PATH

Services
   │
   ├────► PostgreSQL
   ├────► Redis
   ├────► MinIO
   ├────► Kafka
   └────► RabbitMQ


                    ELASTICSEARCH

              ┌─────────────────┐
              │ Elasticsearch   │
              │                 │
              │ Infrastructure  │
              │ staged          │
              └─────────────────┘
                       │
                       X
              No application
              index/query path
```

There is currently:

* no service indexing application data into Elasticsearch
* no service querying Elasticsearch
* no Kubernetes deployment path for Elasticsearch
* no active search workflow dependent on Elasticsearch

Therefore Elasticsearch should **not** be represented as an active persistence dependency in current-state architecture diagrams.

It is infrastructure prepared for a future search capability.

---

# 12. Data Architecture Principles

The data architecture follows these core rules:

### 1. Data ownership

```text
One service
     │
     ▼
Owns its data
     │
     ▼
Owns its database
```

### 2. No shared database access

```text
Service A ───X───► Service B DB
```

### 3. APIs for synchronous reads

```text
Service A ───REST/gRPC───► Service B
```

### 4. Events for asynchronous propagation

```text
Service A ───► Kafka ───► Service B
```

### 5. Local transactions remain local

```text
Service Transaction
       │
       ├── Business Data
       └── Outbox Event
```

### 6. Cache is not source of truth

```text
PostgreSQL = Source of Truth
Redis      = Performance Layer
```

### 7. Object storage is separated from transactional storage

```text
PostgreSQL ──► Metadata / Transactional State
MinIO      ──► Documents / Objects
```

### 8. Storage technology follows workload

```text
Transactional ──► PostgreSQL
Cache          ──► Redis
Events         ──► Kafka
Tasks / Retry  ──► RabbitMQ
Objects        ──► MinIO
Search         ──► Elasticsearch (future)
```

---

# 13. Related Documentation

| Document                                                           | Focus                                       |
| ------------------------------------------------------------------ | ------------------------------------------- |
| [System Context](01-system-context.md)                             | External actors and systems                 |
| [Container Architecture](02-container-architecture.md)             | Runtime containers and infrastructure       |
| [Service Architecture](03-service-architecture.md)                 | Domain ownership and service boundaries     |
| [Communication Architecture](04-communication-architecture.md)     | REST, gRPC, Kafka, RabbitMQ and SOAP        |
| [Event Topology](05-event-topology.md)                             | Event producers, consumers and Kafka topics |
| **Data Architecture**                                              | Data ownership, persistence and storage     |
| [Appointment Booking Sequence](10-appointment-booking-sequence.md) | End-to-end booking workflow                 |

> **Data Architecture:** Each stateful service owns its own data and database. PostgreSQL provides transactional persistence, Redis provides selective caching, MinIO handles objects and archival, Kafka propagates domain events, and RabbitMQ handles notification tasks and retries. Cross-service data access happens through explicit APIs or events rather than shared database access.
