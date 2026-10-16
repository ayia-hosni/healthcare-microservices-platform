# ☁️ AWS Architecture

> **AWS target architecture for the Healthcare Platform**
>
> This document describes how the Healthcare Platform can be deployed on AWS
> using Kubernetes, managed databases, event streaming, queues, object
> storage, workload identity, and cloud-native observability.
>
> The application architecture is intentionally **cloud-independent**:
> business domains, service boundaries, APIs, event contracts, and persistence
> ownership do not change when moving between Azure and AWS.
>
> ⚠️ **Status: Designed — not deployed.**
>
> The currently implemented cloud deployment is Azure. This document defines
> the equivalent AWS target architecture.
>
> See:
>
> - [`../azure/README.md`](../azure/README.md) — implemented Azure architecture
> - [`../../../README.md`](../../../README.md) — project overview
> - [`../../../PROGRESS.md`](../../../PROGRESS.md) — implementation status
> - [`../../../VISION.md`](../../../VISION.md) — future architecture

## Table of Contents

- [Platform Overview](#platform-overview)
- [Architecture at a Glance](#architecture-at-a-glance)
- [Application Architecture](#application-architecture)
- [Request Flow](#request-flow)
- [Event-Driven Architecture](#event-driven-architecture)
- [Messaging Architecture](#messaging-architecture)
- [Data Architecture](#data-architecture)
- [Redis / ElastiCache](#redis--elasticache)
- [Amazon S3](#amazon-s3)
- [AWS Infrastructure](#aws-infrastructure)
- [Network Architecture](#network-architecture)
- [Security Architecture](#security-architecture)
- [Observability](#observability)
- [CI/CD and GitOps](#cicd-and-gitops)
- [Azure → AWS Mapping](#azure--aws-mapping)
- [Production Evolution](#production-evolution)
- [Implementation Status](#implementation-status)
- [Architecture Documentation](#architecture-documentation)
- [Design Principles](#design-principles)

---

# Platform Overview

The Healthcare Platform is a cloud-native healthcare system built around
independently deployable Spring Boot microservices.

The platform separates:

- API traffic
- business domains
- synchronous communication
- asynchronous events
- work queues
- persistence
- caching
- object storage
- identity
- secrets
- observability
- infrastructure

The architecture is designed around **business capabilities first** and
infrastructure second.

```text
                         Healthcare Platform
                                  │
             ┌────────────────────┼────────────────────┐
             │                    │                    │
             ▼                    ▼                    ▼
          Identity             Clinical             Operations
             │                    │                    │
             │          ┌─────────┼─────────┐          │
             │          │         │         │          │
             ▼          ▼         ▼         ▼          ▼
          Patient   Appointment  EMR     Billing   Notification
             │
             └────────────────────┬────────────────────┘
                                  │
                           Domain Events
                                  │
                                  ▼
                              Kafka / MSK
                                  │
                     ┌────────────┼────────────┐
                     ▼            ▼            ▼
                   Audit      Analytics   Notification
```

---

# Architecture at a Glance

```text
┌──────────────────────────────────────────────────────────────────────────────────────────────┐
│                                      INTERNET / USERS                                          │
└───────────────────────────────────────────┬──────────────────────────────────────────────────┘
                                            │
                                            ▼
                                    ┌───────────────┐
                                    │    Route 53   │
                                    │      DNS      │
                                    └───────┬───────┘
                                            │
                                            ▼
                                    ┌───────────────┐
                                    │      WAF      │
                                    │ Web Protection│
                                    └───────┬───────┘
                                            │
                                            ▼
                                    ┌───────────────┐
                                    │      ALB      │
                                    │ TLS / Ingress │
                                    └───────┬───────┘
                                            │
════════════════════════════════════════════╪═════════════════════════════════════════════════════
                                          AWS VPC
════════════════════════════════════════════╪═════════════════════════════════════════════════════
                                            │
                                            ▼
                                    ┌───────────────┐
                                    │      EKS      │
                                    │  Kubernetes   │
                                    └───────┬───────┘
                                            │
                         ┌──────────────────┼──────────────────┐
                         │                  │                  │
                         ▼                  ▼                  ▼
                  GraphQL Gateway     Spring Boot        Platform Pods
                                          Services
                         │
          ┌──────────────┼───────────────────────────┐
          │              │                           │
          ▼              ▼                           ▼
     Identity        Patient / Doctor          Appointment
                                                   │
                                ┌──────────────────┼──────────────────┐
                                │                  │                  │
                                ▼                  ▼                  ▼
                              EMR                Billing        Notification
                                │                  │                  │
                                └──────────────────┼──────────────────┘
                                                   │
                                            Domain Events
                                                   │
                                                   ▼
                                           ┌──────────────┐
                                           │ Amazon MSK    │
                                           │    Kafka      │
                                           └──────┬───────┘
                                                  │
                              ┌───────────────────┼───────────────────┐
                              │                   │                   │
                              ▼                   ▼                   ▼
                           Audit             Analytics          Notification
                                                                      │
                                                                      ▼
                                                                 Work Queue
                                                                      │
                                                        ┌─────────────┴─────────────┐
                                                        │                           │
                                                        ▼                           ▼
                                                    RabbitMQ                       SQS
                                                   current model              AWS alternative
                                                        │                           │
                                                        ▼                           ▼
                                                     Worker                       Worker
                                                        │                           │
                                                        ▼                           ▼
                                                       DLQ                         DLQ


══════════════════════════════════════════════════════════════════════════════════════════════════
                                  AWS MANAGED SERVICES
══════════════════════════════════════════════════════════════════════════════════════════════════

      ┌────────────────┐     ┌──────────────────┐     ┌────────────────┐
      │ RDS PostgreSQL │     │ ElastiCache Redis│     │   Amazon S3    │
      │                │     │                  │     │                │
      │ Service DBs    │     │ Cache / Locks    │     │ EMR Documents  │
      └────────────────┘     └──────────────────┘     └────────────────┘

      ┌────────────────┐     ┌──────────────────┐     ┌────────────────┐
      │ Secrets Manager│     │       ECR        │     │   CloudWatch   │
      │                │     │ Docker Images    │     │ Logs / Infra   │
      └────────────────┘     └──────────────────┘     └────────────────┘


══════════════════════════════════════════════════════════════════════════════════════════════════
                              EXTERNAL INTEGRATION / FUTURE
══════════════════════════════════════════════════════════════════════════════════════════════════

                              Domain / Integration Event
                                         │
                                         ▼
                                   ┌────────────┐
                                   │     SNS    │
                                   │    Topic   │
                                   └─────┬──────┘
                                         │
                         ┌───────────────┼────────────────┐
                         │               │                │
                         ▼               ▼                ▼
                       SQS Queue     HTTPS Webhook      Email / Ops
                         │               │                │
                         ▼               ▼                ▼
                       Worker       Partner Hospital   Operations
                                      / Lab / Payer


══════════════════════════════════════════════════════════════════════════════════════════════════
                                  OBSERVABILITY
══════════════════════════════════════════════════════════════════════════════════════════════════

                     ┌──────────────────────────────────┐
                     │              EKS                  │
                     │                                  │
                     │ Spring Boot / Kubernetes Workloads│
                     └───────────────┬──────────────────┘
                                     │
                       ┌─────────────┼─────────────┐
                       │             │             │
                       ▼             ▼             ▼
                    Metrics         Logs         Traces
                       │             │             │
                       ▼             ▼             ▼
                  Prometheus     CloudWatch      Zipkin
                       │
                       ▼
                    Grafana
```

> RabbitMQ/SQS and Kafka/MSK solve different problems and SNS is a separate
> integration capability — see [Messaging Architecture](#messaging-architecture)
> for why none of these substitute for one another.

---

# Application Architecture

The application consists of domain-oriented Spring Boot services.

```text
                              GraphQL Gateway
                                     │
                ┌────────────────────┼────────────────────┐
                │                    │                    │
                ▼                    ▼                    ▼
           Identity Service     Patient Service     Doctor Service
                │                    │                    │
                └────────────────────┼────────────────────┘
                                     │
                                     ▼
                            Appointment Service
                                     │
                 ┌───────────────────┼───────────────────┐
                 │                   │                   │
                 ▼                   ▼                   ▼
             EMR Service       Billing Service     Notification
                 │                   │                   │
                 └───────────────────┼───────────────────┘
                                     │
                              Domain Events
                                     │
                                     ▼
                                 Kafka / MSK
                                     │
                         ┌───────────┼───────────┐
                         ▼           ▼           ▼
                       Audit      Analytics   Notification
```

### Services

| Service         | Responsibility                       |
| --------------- | ------------------------------------ |
| Identity        | Authentication and authorization     |
| Patient         | Patient information                  |
| Doctor          | Healthcare provider information      |
| Appointment     | Appointment lifecycle and scheduling |
| EMR             | Electronic medical records           |
| Billing         | Billing workflows                    |
| Notification    | Notification processing              |
| Audit           | Audit trail                          |
| Analytics       | Analytical processing                |
| GraphQL Gateway | Client-facing API aggregation        |

---

# Request Flow

Synchronous operations use REST/gRPC communication when an immediate result
is required.

```text
Client
  │
  │ HTTPS
  ▼
Route 53
  │
  ▼
WAF
  │
  ▼
ALB
  │
  ▼
GraphQL Gateway
  │
  │ REST / gRPC
  ▼
Appointment Service
  │
  ├──────────────► Patient Service
  │
  ├──────────────► Doctor Service
  │
  └──────────────► Appointment Database
```

The request remains synchronous only where the caller needs an immediate
answer.

---

# Event-Driven Architecture

Long-running or independent downstream work is handled asynchronously.

```text
Client
  │
  ▼
Appointment Service
  │
  ├──────────────► PostgreSQL
  │
  └──────────────► AppointmentCreated
                           │
                           ▼
                       Kafka / MSK
                           │
             ┌─────────────┼─────────────┐
             │             │             │
             ▼             ▼             ▼
           Audit        Analytics    Notification
```

This provides loose coupling, independent consumers, asynchronous
processing, event retention, replay capability, and independent scaling.

---

# Messaging Architecture

The platform deliberately uses different messaging abstractions for
different requirements — Kafka/MSK, RabbitMQ, SQS, and SNS are **not
interchangeable**, and none of them is added just because it's available on
AWS.

```text
                         Messaging Requirements
                                  │
                 ┌────────────────┼────────────────┐
                 │                │                │
                 ▼                ▼                ▼
          Event Streaming      Work Queue      External Fan-Out
                 │                │                │
                 ▼                ▼                ▼
             Kafka/MSK       RabbitMQ/SQS         SNS
```

| Technology  | Primary Role       |            Consumers |                    Replay |                 DLQ | Typical Use                |
| ----------- | ------------------ | --------------------: | -------------------------: | --------------------: | --------------------------- |
| Kafka / MSK | Event streaming    |                  Many |                          ✅ |                ✅ DLT | Domain events              |
| RabbitMQ    | Work queue         |      Worker-oriented |                   Limited |                    ✅ | Current notification queue |
| SQS         | Managed work queue |      Worker-oriented | No event-stream semantics |             ✅ Native | AWS-managed async jobs      |
| SNS         | Pub/sub fan-out    | Many / heterogeneous |                        No | Through subscribers | External integrations      |

## Kafka / Amazon MSK

Kafka is the platform's **internal event backbone**. It's preferred whenever
multiple services need the same event, consumers process independently,
events need retention, replay/reprocessing is valuable, or producer and
consumer lifecycles should be decoupled.

```text
AppointmentCreated
       │
       ├──► Audit
       ├──► Analytics
       └──► Notification
```

A new consumer can be added without changing the appointment producer.

## RabbitMQ

RabbitMQ represents a **work queue**, not the platform-wide event backbone —
appropriate when one logical worker processes a job, queue semantics matter
more than event history, retry/DLQ behavior is required, and the workload is
task-oriented (today: the notification queue only).

```text
Notification Job
       │
       ▼
   RabbitMQ
       │
       ▼
 Notification Worker
       │
       ├──────────► Success
       │
       └──────────► Failure ──► DLQ
```

### The scenario that actually needs it

RabbitMQ isn't there because "queues are best practice" in the abstract —
`audit-service` and `analytics-service` consume the same kind of Kafka
topics with **no queue at all**, just Kafka's own consumer + DLT, and that's
correctly sufficient for them. The difference is what happens *inside* the
consumer:

```text
audit-service / analytics-service consumer:
  read event ──► write one row to its own DB ──► done
  (fast, local, no external dependency — Kafka's own retry/DLT is enough)

notification-service consumer:
  read event ──► call an external email/SMS/push provider over HTTP
  (slow, unreliable, rate-limited, third-party-owned)
```

That difference matters because of how Kafka consumption actually works:
**a partition is consumed in order, by one consumer, one record at a time.**
If `notification-service` called the SMS provider directly from its Kafka
listener with no queue in between, one slow or hanging provider response
blocks that partition — every notification behind it in the same partition
waits, even ones for unrelated patients that would otherwise send instantly.
A provider outage doesn't fail one message; it stalls the whole topic.

```text
Without a queue (direct call from the Kafka consumer):

Kafka partition:  [msg1] [msg2] [msg3] [msg4] ...
                     │
                     ▼
              call SMS provider ── provider is slow/down ── consumer blocked
                     │
              msg2, msg3, msg4 all wait behind msg1,
              even though they have nothing to do with it


With a queue (RabbitMQ/SQS) between Kafka and the provider call:

Kafka partition:  [msg1] [msg2] [msg3] [msg4] ...
                     │       │      │      │
                     ▼       ▼      ▼      ▼
                  handed off to the queue immediately — consumer keeps reading
                     │
                     ▼
              N workers pull from the queue independently, each retrying
              its own message on its own backoff timer, without blocking
              the others or the Kafka consumer behind them
```

That's the concrete, non-over-engineered reason a queue sits between Kafka
and the actual send: it isolates a slow/unreliable **external** dependency
from the shared event stream, and gives each failed message its own retry
timer instead of one shared consumer-level retry. Neither `audit-service`
nor `analytics-service` has that problem, which is exactly why they don't
have a queue either — the pattern is applied where the failure mode
actually requires it, not uniformly.

## Amazon SQS

SQS is the AWS-managed alternative for the same queue-oriented role RabbitMQ
plays today:

```text
Kafka / MSK                              SQS
────────────                             ────────────
"Something happened."                    "Please process this job."

AppointmentCreated                       SendNotification
PatientUpdated                           GenerateReport
ReferralCreated                          ProcessDocument

Multiple independent consumers           Worker-oriented
Event retention                          Retry
Replay                                   DLQ
```

SQS is worth adopting specifically because its native DLQ
(`maxReceiveCount`) works out of the box, unlike the platform's current
RabbitMQ `x-death`-header check, which is wired up in `RabbitMqConfig`'s own
comment but never actually read by `NotificationJobListener` (see
[`../../architecture/supporting-services.md`](../../architecture/supporting-services.md)).
It's a **replacement for RabbitMQ**, not a replacement for Kafka/MSK.

## Amazon SNS

SNS is useful when one message must reach multiple heterogeneous or
external subscribers — something neither Kafka nor SQS is built for, since
both assume a persistent client on the other end, and an external partner's
webhook receiver isn't one.

```text
ReferralCreated
      │
      ▼
     SNS
      │
      ├────────► SQS ───────► Internal Processing
      │
      ├────────► HTTPS ─────► Partner Hospital
      │
      └────────► Email ─────► Operations
```

No production case for this exists yet — it becomes relevant once the
platform builds the external hospital/lab/insurance interoperability item
tracked as not-started in [`../../../VISION.md`](../../../VISION.md).

## Why Not All Four Everywhere

**Do not** reach for every messaging technology just because it's available:

```text
Kafka
  │
  ├──► RabbitMQ
  ├──► SQS
  └──► SNS
```

Instead, match the technology to the requirement:

```text
                    Requirement
                        │
          ┌─────────────┼─────────────┐
          ▼             ▼             ▼
       Events         Jobs        External Fan-Out
          │             │             │
       Kafka        RabbitMQ/SQS      SNS
```

SQS and SNS should not appear in an active "Platform Components" list next
to Kafka and RabbitMQ — they're evaluated options with a concrete trigger
condition each (above), not deployed components. See
[Implementation Status](#implementation-status) for what's actually running.

---

# Data Architecture

The platform follows **database-per-service** ownership.

```text
                         Amazon RDS
                        PostgreSQL
                             │
        ┌────────────────────┼────────────────────┐
        │                    │                    │
        ▼                    ▼                    ▼
   identity_db          patient_db          doctor_db
        │                    │                    │
    Identity              Patient              Doctor


        ┌────────────────────┼────────────────────┐
        │                    │                    │
        ▼                    ▼                    ▼
 appointment_db           emr_db            billing_db
```

The same principle applies to supporting services:

```text
Appointment Service ───► appointment_db
Patient Service ───────► patient_db
Billing Service ───────► billing_db

❌ Shared business database
❌ Cross-service table ownership
❌ Direct access to another service's database
```

---

# Redis / ElastiCache

Redis provides low-latency infrastructure capabilities.

```text
                    Spring Boot Services
                            │
                            ▼
                   ElastiCache Redis
                            │
              ┌─────────────┼─────────────┐
              ▼             ▼             ▼
            Cache         Locks       Temporary State
```

Redis is not the source of truth for business data.

---

# Amazon S3

Large objects are stored separately from relational data.

```text
                        EMR Service
                             │
                             ▼
                         Amazon S3
                             │
               ┌─────────────┼─────────────┐
               ▼             ▼             ▼
          Attachments     Documents      Exports
```

---

# AWS Infrastructure

## Container Lifecycle

```text
Developer
    │
    ▼
Git Repository
    │
    ▼
CI Pipeline
    │
    ▼
Docker Build
    │
    ▼
Amazon ECR
    │
    ▼
Argo CD / GitOps
    │
    ▼
Amazon EKS
    │
    ▼
Running Workloads
```

---

# Network Architecture

```text
                              INTERNET
                                  │
                                  ▼
                              Route 53
                                  │
                                  ▼
                                WAF
                                  │
                                  ▼
                                ALB
                                  │
══════════════════════════════════╪══════════════════════════════════
                               AWS VPC
══════════════════════════════════╪══════════════════════════════════
                                  │
                 ┌────────────────┴────────────────┐
                 │                                 │
                 ▼                                 ▼
           PUBLIC SUBNETS                    PRIVATE SUBNETS
                 │                                 │
              ┌──▼──┐                         ┌────▼────┐
              │ ALB │                         │   EKS   │
              └─────┘                         └────┬────┘
                                                   │
                              ┌────────────────────┼────────────────────┐
                              │                    │                    │
                              ▼                    ▼                    ▼
                             RDS                Redis                  MSK
```

### Network goals

* public ingress only through the load balancer;
* private application workloads;
* private databases;
* controlled outbound traffic;
* security-group isolation;
* Kubernetes network policies;
* clear path toward Multi-AZ production deployment.

---

# Security Architecture

The platform uses workload identity instead of static AWS credentials.

```text
                         EKS Pod
                            │
                            ▼
                  Kubernetes ServiceAccount
                            │
                            ▼
                           OIDC
                            │
                            ▼
                         IAM Role
                            │
              ┌─────────────┼─────────────┐
              ▼             ▼             ▼
             S3       Secrets Manager   CloudWatch
```

### Credential model

```text
❌ Static access keys
❌ Credentials in containers
❌ Hard-coded secrets

                VS

✅ Kubernetes ServiceAccount
             ↓
         OIDC / IAM
             ↓
    Temporary AWS credentials
             ↓
       Least privilege
```

---

# Observability

Observability is divided into metrics, logs, and distributed traces.

```text
                           EKS
                            │
              ┌─────────────┼─────────────┐
              │             │             │
              ▼             ▼             ▼
           Metrics         Logs         Traces
              │             │             │
              ▼             ▼             ▼
         Prometheus     CloudWatch      Zipkin
              │
              ▼
           Grafana
```

### Metrics

```text
Spring Boot ──► Prometheus ──► Grafana
```

### Logs

```text
Containers ──► CloudWatch Logs
```

### Distributed tracing

```text
GraphQL Gateway
      │
      ├────► Patient
      │
      ├────► Appointment
      │
      └────► Billing ──► Zipkin
```

---

# CI/CD and GitOps

The target deployment follows an image-build and GitOps model.

```text
                     Developer
                         │
                         ▼
                    Git Push
                         │
                         ▼
                 GitHub Actions
                         │
              ┌──────────┼──────────┐
              │          │          │
              ▼          ▼          ▼
            Tests      Security    Build
                         │
                         ▼
                    Docker Image
                         │
                         ▼
                        ECR
                         │
                         ▼
                      Argo CD
                         │
                         ▼
                        EKS
```

Infrastructure itself is managed using Terraform:

```text
Terraform
    │
    ▼
AWS Infrastructure
    │
    ├── VPC
    ├── EKS
    ├── RDS
    ├── ElastiCache
    ├── MSK
    ├── S3
    ├── ECR
    └── IAM
```

---

# Azure → AWS Mapping

The AWS architecture mirrors the existing Azure deployment.

| Concern           | Azure — Implemented           | AWS — Target           |
| ------------------ | ------------------------------ | ------------------------ |
| Kubernetes        | AKS                           | EKS                    |
| Registry          | Azure Container Registry      | ECR                    |
| PostgreSQL        | Azure Database for PostgreSQL | RDS                    |
| Redis             | Azure Cache for Redis         | ElastiCache            |
| Event Streaming   | Azure Event Hubs              | MSK                    |
| Object Storage    | Azure Blob Storage            | S3                     |
| Secrets           | Azure Key Vault               | Secrets Manager        |
| Network           | Azure VNet                    | VPC                    |
| Workload Identity | Azure Workload Identity       | EKS OIDC + IAM         |
| Load Balancing    | NGINX / Azure networking      | ALB                    |
| DNS               | Azure DNS                     | Route 53               |
| TLS               | Azure certificates            | ACM                    |
| Logs              | Log Analytics                 | CloudWatch             |
| Policy            | Azure Policy                  | Kyverno / OPA          |
| RabbitMQ          | Self-hosted Kubernetes        | Self-hosted Kubernetes |
| Elasticsearch     | Self-hosted Kubernetes        | Self-hosted Kubernetes |

The important architectural principle: the cloud provider changes, **the
business architecture does not.**

```text
                  SAME APPLICATION
                         │
             ┌───────────┴───────────┐
             │                       │
           Azure                    AWS
             │                       │
            AKS                     EKS
             │                       │
        Event Hubs                  MSK
             │                       │
        PostgreSQL                  RDS
             │                       │
          Blob Storage               S3
```

---

# Production Evolution

## Phase 1 — Cost-Constrained POC

```text
                         EKS
                          │
             ┌────────────┼────────────┐
             ▼            ▼            ▼
            RDS         Redis          MSK
             │
             ▼
             S3

       Secrets Manager
              │
              ▼
             ECR

       RabbitMQ
       self-hosted
```

The goal is to validate the architecture while controlling operational and
cloud costs.

## Phase 2 — Production Hardening

```text
                         Route 53
                            │
                            ▼
                           WAF
                            │
                            ▼
                           ALB
                            │
                            ▼
                           EKS
                            │
               ┌────────────┼────────────┐
               ▼            ▼            ▼
              RDS         Redis          MSK
               │
          ┌────┼────┐
          ▼    ▼    ▼
       Backups  HA  Monitoring
```

Introduce: private subnets, Multi-AZ deployment, automated TLS, secret
rotation, stronger network segmentation, backup validation, disaster
recovery testing, autoscaling, and operational alerting.

## Phase 3 — Multi-Region

Multi-region is requirement-driven rather than enabled by default.

```text
                         Route 53
                            │
                 ┌──────────┴──────────┐
                 │                     │
                 ▼                     ▼
             Region A              Region B
                 │                     │
                ALB                   ALB
                 │                     │
                EKS                   EKS
                 │                     │
             ┌───┴───┐             ┌───┴───┐
             ▼       ▼             ▼       ▼
            RDS     MSK           RDS     MSK
             │                     │
             └──────────┬──────────┘
                        │
                  Replication / DR
```

Multi-region adds complexity around database replication, Kafka
replication, traffic routing, consistency, failover, secrets,
observability, and operational ownership — it should be introduced only
when availability requirements justify that complexity.

---

# Implementation Status

| Component              | AWS Status                   | Application Status      |
| ------------------------ | ------------------------------ | -------------------------- |
| EKS                    | 📋 Designed                  | ✅ Kubernetes-based      |
| ECR                    | 📋 Designed                  | ✅ Docker images         |
| RDS PostgreSQL         | 📋 Designed                  | ✅ PostgreSQL            |
| ElastiCache Redis      | 📋 Designed                  | ✅ Redis                 |
| Amazon MSK             | 📋 Designed                  | ✅ Kafka architecture    |
| RabbitMQ               | 📋 Designed                  | ✅ Application component |
| Amazon SQS             | 📋 Future option             | —                       |
| Amazon SNS             | 📋 Future integration option | —                       |
| Amazon S3              | 📋 Designed                  | 🔄 Object storage       |
| Secrets Manager        | 📋 Designed                  | 🔄 Cloud-specific       |
| Route 53               | 📋 Designed                  | 🔄 Cloud-specific       |
| WAF                    | 📋 Designed                  | 🔄 Cloud-specific       |
| ALB                    | 📋 Designed                  | 🔄 Cloud-specific       |
| CloudWatch             | 📋 Designed                  | 🔄 Cloud-specific       |
| Prometheus             | ✅ Cloud-independent          | ✅                       |
| Grafana                | ✅ Cloud-independent          | ✅                       |
| Zipkin                 | ✅ Cloud-independent          | ✅                       |
| Terraform AWS Provider | ❌ Not implemented            | —                       |
| Multi-region           | 📋 Planned                   | —                       |

SQS and SNS are intentionally **not presented as currently deployed
components** — see [Messaging Architecture](#messaging-architecture) for
the specific trigger condition each one is waiting on.

---

# Architecture Documentation

| Document                                       | Focus                                   |
| ------------------------------------------------ | ------------------------------------------ |
| [`compute.md`](compute.md)                     | EKS, nodes, scaling, Terraform, CI/CD   |
| [`networking.md`](networking.md)               | VPC, subnets, ingress, egress, security |
| [`data-services.md`](data-services.md)         | RDS, Redis, MSK, RabbitMQ, S3           |
| [`identity-security.md`](identity-security.md) | IAM, OIDC, Secrets Manager, security    |
| [`observability.md`](observability.md)         | Metrics, logs, traces, dashboards       |

---

# Design Principles

## 1. Business domains first

```text
Business Domains ──► Microservices ──► Infrastructure
```

Infrastructure should support the domain architecture rather than define it.

## 2. Synchronous when the caller needs an answer

```text
Immediate result required ──► REST/gRPC
```

## 3. Events when services can react independently

```text
No immediate response required ──► Domain Event ──► Kafka / MSK
```

## 4. Queues for work

```text
Process this job ──► RabbitMQ / SQS ──► Worker ──► DLQ
```

## 5. SNS for heterogeneous fan-out

```text
One event ──► SNS ──► SQS / Webhook / Email
```

## 6. Managed services where they provide real value

```text
EKS
 │
 ├── RDS
 ├── ElastiCache
 ├── MSK
 ├── S3
 ├── ECR
 └── Secrets Manager
```

Managed services reduce operational overhead while keeping the application
architecture portable.

## 7. Security through identity and isolation

```text
EKS Workload ──► ServiceAccount ──► OIDC ──► IAM ──► Least-Privilege Access
```

## 8. Observability is part of the architecture

```text
Services
   │
   ├── Metrics ──► Prometheus ──► Grafana
   ├── Logs ─────► CloudWatch
   └── Traces ───► Zipkin
```
