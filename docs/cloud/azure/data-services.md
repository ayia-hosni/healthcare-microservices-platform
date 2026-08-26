# Azure Data Services

> Part of [Azure Architecture](README.md). Covers PostgreSQL, Redis, Event Hubs, RabbitMQ, and
> Blob Storage — the data and messaging layer — plus the reliability patterns that hold events
> together across service boundaries.

---

## Table of Contents

- [PostgreSQL](#postgresql)
- [Transaction Boundaries](#transaction-boundaries)
- [Production Database Evolution](#production-database-evolution)
- [Caching — Azure Cache for Redis](#caching--azure-cache-for-redis)
- [Messaging — Azure Event Hubs](#messaging--azure-event-hubs)
- [RabbitMQ](#rabbitmq)
- [Object Storage — Azure Blob Storage](#object-storage--azure-blob-storage)
- [Data & Event Reliability](#data--event-reliability)

---

# PostgreSQL

PostgreSQL is the transactional source of truth.

```text
                    PostgreSQL
                         │
        ┌────────────────┼────────────────┐
        │                │                │
        ▼                ▼                ▼
   Identity DB      Patient DB       Doctor DB
        │                │                │
        ▼                ▼                ▼
 Appointment DB      EMR DB          Billing DB
        │                │                │
        ▼                ▼                ▼
Notification DB     Audit DB       Analytics DB
```

The application follows a database-per-service ownership model: a
dedicated database, login role, and credentials per service, with no
direct cross-service queries. Cross-service information is exchanged
through APIs or domain events.

---

# Transaction Boundaries

Transactions are local to a service.

```text
Appointment Service
        │
        ▼
appointment_db
        │
        ▼
Commit
        │
        ▼
appointment.events
```

Cross-service workflows use events rather than distributed database
transactions. This avoids coupling services through two-phase commit.

---

# Production Database Evolution

The database architecture can scale progressively:

```text
Shared PostgreSQL Server
          │
          ▼
Database-per-Service
          │
          ▼
Independent Database Instances
          │
          ├── Read Replicas
          ├── HA
          ├── Connection Pooling
          └── Service-specific Scaling
```

The logical isolation model remains unchanged while infrastructure
isolation can increase with workload requirements. Compute-side scaling
(pods, node pools) is covered in [Compute](compute.md#availability--scaling).

---

# Caching — Azure Cache for Redis

Redis is used as a performance optimization.

```text
Request
   │
   ▼
Service
   │
   ▼
Redis
 ┌─┴─┐
 │   │
Hit Miss
 │   │
 │   ▼
 │ PostgreSQL
 │   │
 └───┘
```

Cache entries are disposable. PostgreSQL remains the authoritative source.

The architecture supports:

* TTL-based expiration
* cache-aside
* selective invalidation
* read-through patterns
* distributed locks where required

Redis is currently used primarily for read-heavy workloads such as patient
and doctor lookups.

---

# Messaging — Azure Event Hubs

Azure Event Hubs provides the Kafka-compatible event backbone.

## Topics

```text
patient.events
doctor.events
appointment.events
emr.events
billing.events
notification.requests
audit.events
```

The event topology follows business domains rather than infrastructure
components.

## Event Flow

```text
Appointment Service
       │
       ▼
appointment.events
       │
       ├──────────► Notification Service
       │
       ├──────────► Billing Service
       │
       ├──────────► Audit Service
       │
       └──────────► Analytics Service
```

Consumers process events independently.

## Event Design

Domain events should contain:

```text
eventId
eventType
eventVersion
aggregateId
timestamp
correlationId
producer
payload
```

Example:

```json
{
  "eventId": "uuid",
  "eventType": "AppointmentCreated",
  "eventVersion": 1,
  "aggregateId": "appointment-id",
  "timestamp": "2026-08-25T18:00:00Z",
  "correlationId": "request-id",
  "producer": "appointment-service",
  "payload": {}
}
```

Versioned events allow consumers to evolve independently.

---

# RabbitMQ

RabbitMQ handles queue-oriented messaging where traditional queue semantics
are more appropriate than event streaming.

```text
Notification Service
        │
        ▼
     RabbitMQ
        │
        ├── Retry Queue
        │
        ├── Dead Letter Queue
        │
        └── Notification Worker
```

RabbitMQ and Event Hubs have distinct responsibilities:

| Technology | Responsibility                  |
| ---------- | -------------------------------- |
| Event Hubs | Domain events / event streaming |
| RabbitMQ   | Work queues / retries / DLQ     |

This avoids using a single messaging technology for fundamentally different
communication patterns.

---

# Object Storage — Azure Blob Storage

Azure Blob Storage provides durable storage for clinical documents and
attachments.

```text
                 EMR Service
                     │
                     ▼
             Azure Blob Storage
                     │
          ┌──────────┼──────────┐
          ▼          ▼          ▼
      Documents   Attachments   Files
```

The database stores metadata and object references rather than large binary
payloads.

```text
Document
├── id
├── patientId
├── objectKey
├── contentType
├── size
├── checksum
└── createdAt
```

This keeps transactional data and object storage responsibilities separate.
The storage account itself replaces MinIO; `emr-service`'s document client
is moving from the MinIO SDK it uses locally to the native Azure Blob SDK
(`com.azure:azure-storage-blob`) as part of that same transition, so both
paths converge on the metadata model above.

---

# Data & Event Reliability

Distributed systems require explicit reliability mechanisms beyond what a
single database transaction can guarantee.

## Idempotent Consumers

Consumers should safely process the same event more than once.

```text
Event
 │
 ▼
Consumer
 │
 ├── Already processed? ──► Ignore
 │
 └── New event
        │
        ▼
     Process
        │
        ▼
   Mark processed
```

## Retry Strategy

Transient failures should use bounded retries.

```text
Message
   │
   ▼
Consumer
   │
   ├── Success ─────► Complete
   │
   └── Failure
          │
          ▼
       Retry
          │
          ▼
     Retry limit
          │
          ▼
      Dead Letter
```

This provides eventual consistency while preserving service autonomy — see
[Transaction Boundaries](#transaction-boundaries) above for the commit-then-publish
pattern this builds on.
