# AWS Data Services

> Part of [AWS Architecture](README.md). 🏗️ Designed, not deployed — see the banner there.
> Covers RDS PostgreSQL, ElastiCache, MSK, RabbitMQ, and S3 — the data and messaging layer —
> plus the reliability patterns that hold events together across service boundaries.

---

## Table of Contents

- [PostgreSQL — Amazon RDS](#postgresql--amazon-rds)
- [Transaction Boundaries](#transaction-boundaries)
- [Production Database Evolution](#production-database-evolution)
- [Caching — Amazon ElastiCache for Redis](#caching--amazon-elasticache-for-redis)
- [Messaging — Amazon MSK](#messaging--amazon-msk)
- [RabbitMQ](#rabbitmq)
- [Object Storage — Amazon S3](#object-storage--amazon-s3)
- [Data & Event Reliability](#data--event-reliability)

---

# PostgreSQL — Amazon RDS

PostgreSQL is the transactional source of truth.

```text
                    RDS PostgreSQL
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

The application follows a database-per-service ownership model. A cost-
constrained deployment can run all nine databases on one RDS instance under
separate roles/credentials — the same POC posture the current Azure
deployment uses — without changing the ownership model itself.

RDS should sit on private subnets, reachable only from EKS workloads over
VPC networking. The one exception is provisioning: Terraform connects to
PostgreSQL directly to create the per-service databases and roles, which is
a provisioning-time concern, not a runtime requirement — it can be solved
with a bastion host, AWS Systems Manager, or a provisioning job run from
inside the VPC, without exposing RDS publicly at runtime.

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

```text
Shared RDS Instance
          │
          ▼
Database-per-Service
          │
          ▼
Independent RDS Instances
          │
          ├── Read Replicas
          ├── Multi-AZ
          ├── Connection Pooling
          └── Service-specific Scaling
```

The logical isolation model remains unchanged while infrastructure
isolation can increase with workload requirements. Compute-side scaling
(pods, node groups) is covered in [Compute](compute.md#availability--scaling).

---

# Caching — Amazon ElastiCache for Redis

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

The architecture supports TTL-based expiration, cache-aside, selective
invalidation, read-through patterns, and distributed locks where required.
Redis is currently used primarily for read-heavy workloads such as patient
and doctor lookups, so the architecture should tolerate cache loss and
rebuild cached data from PostgreSQL rather than treating ElastiCache as a
system of record.

---

# Messaging — Amazon MSK

Amazon MSK provides the Kafka-compatible event backbone.

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

## Kafka Authentication

MSK IAM authentication is the preferred mechanism, letting workloads
authenticate with their existing IAM role instead of distributing
long-lived Kafka usernames/passwords. If SASL/SCRAM is used instead,
credentials belong in Secrets Manager, accessed the same way database
credentials are.

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

RabbitMQ and MSK have distinct responsibilities:

| Technology | Responsibility                  |
| ---------- | -------------------------------- |
| MSK        | Domain events / event streaming |
| RabbitMQ   | Work queues / retries / DLQ     |

RabbitMQ stays self-hosted inside EKS rather than moving to Amazon MQ —
it's currently an internal infrastructure component with no requirement to
change its operational model. Amazon MQ can be evaluated later if a
managed RabbitMQ becomes operationally valuable.

---

# Object Storage — Amazon S3

Amazon S3 provides durable storage for clinical documents and attachments.

```text
                 EMR Service
                     │
                     ▼
                 Amazon S3
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
S3 replaces MinIO; `emr-service`'s document client would move from the
MinIO SDK it uses locally to the AWS SDK's S3 client
(`software.amazon.awssdk:s3`), so both paths converge on the metadata model
above. Recommended controls: Block Public Access, server-side encryption,
bucket versioning, lifecycle policies, IAM-based access, and optional
Object Lock for audit/compliance requirements.

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
[Transaction Boundaries](#transaction-boundaries) above for the
commit-then-publish pattern this builds on.
