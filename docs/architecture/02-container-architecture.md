# Container Architecture

The **Container Architecture** is the **C4 Level 2** view of the Healthcare Microservices Platform.

It describes the independently deployable runtime units inside the platform, how traffic enters the system, how services communicate synchronously and asynchronously, and where application data is persisted.

Each service container is independently deployable and owns a specific business capability.

For domain-level responsibilities, see [Service Architecture](03-service-architecture.md).

---

## Table of Contents

1. [Container Architecture Diagram](#1-container-architecture-diagram)
2. [External Access Path](#2-external-access-path)
3. [Application Containers](#3-application-containers)
4. [Synchronous Communication](#4-synchronous-communication)
5. [Event-Driven Communication](#5-event-driven-communication)
6. [Messaging Infrastructure](#6-messaging-infrastructure)
7. [Data Storage](#7-data-storage)
8. [Internal-Only Containers](#8-internal-only-containers)
9. [Container Responsibilities](#9-container-responsibilities)
10. [Architecture Principles](#10-architecture-principles)
11. [Related Architecture Documentation](#11-related-architecture-documentation)

---

## 1. Container Architecture Diagram

```text
╔══════════════════════════════════════════════════════════════════════════════╗
║                              CLIENT LAYER                                    ║
╚══════════════════════════════════════════════════════════════════════════════╝

                         ┌──────────────────────┐
                         │    Client Browser    │
                         └──────────┬───────────┘
                                    │
                                    │ HTTP / HTTPS
                                    ▼
                         ┌──────────────────────┐
                         │     Frontend SPA     │
                         │   Angular / Static   │
                         └──────────┬───────────┘
                                    │
                                    ▼
                         ┌──────────────────────┐
                         │     NGINX Ingress    │
                         │ Single External Entry│
                         │       Point          │
                         └──────────┬───────────┘
                                    │
╔═══════════════════════════════════╪══════════════════════════════════════════╗
║                    KUBERNETES CLUSTER                                      ║
║                                   │                                          ║
║       healthcare-platform namespace                                         ║
║                                   │                                          ║
║             ┌─────────────────────┼──────────────────────┐                  ║
║             │                     │                      │                  ║
║             ▼                     ▼                      ▼                  ║
║    ┌────────────────┐    ┌────────────────┐    ┌──────────────────┐         ║
║    │ identity       │    │ patient        │    │ doctor           │         ║
║    │ :8081          │    │ :8082          │    │ :8083            │         ║
║    │                │    │                │    │                  │         ║
║    │ Auth / RBAC    │    │ Patient data   │    │ Doctors / Staff  │         ║
║    └───────┬────────┘    └───────┬────────┘    └────────┬─────────┘         ║
║            │                     │                      │                   ║
║            ▼                     ▼                      ▼                   ║
║    ┌───────────────┐     ┌───────────────┐     ┌───────────────┐            ║
║    │ identity_db   │     │ patient_db    │     │ doctor_db     │            ║
║    └───────────────┘     └───────────────┘     └───────────────┘            ║
║                                                                              ║
║    ┌──────────────────────┐          ┌──────────────────────┐               ║
║    │ appointment-service  │          │ graphql-gateway      │               ║
║    │ :8084                │          │ :8090                │               ║
║    │                      │          │                      │               ║
║    │ Scheduling           │          │ GraphQL BFF           │               ║
║    └──────────┬───────────┘          └──────────┬───────────┘               ║
║               │                                 │                            ║
║               ▼                         ┌───────┴────────┐                   ║
║    ┌─────────────────────┐              │ gRPC / REST    │                   ║
║    │ appointment_db      │              ▼                ▼                   ║
║    └─────────────────────┘       ┌────────────┐  ┌────────────┐             ║
║                                  │  Patient   │  │  Doctor    │             ║
║                                  └────────────┘  └────────────┘             ║
║                                           │                                  ║
║                                           ▼                                  ║
║                                  ┌────────────────┐                          ║
║                                  │ billing-service│                          ║
║                                  │ :8086          │                          ║
║                                  └───────┬────────┘                          ║
║                                          │                                   ║
║                                          ▼                                   ║
║                                  ┌────────────────┐                          ║
║                                  │   billing_db   │                          ║
║                                  └────────────────┘                          ║
║                                                                              ║
║    ┌────────────────────┐                                                    ║
║    │   emr-service      │                                                    ║
║    │      :8085         │                                                    ║
║    │                    │                                                    ║
║    │ Encounters / EMR   │                                                    ║
║    └─────────┬──────────┘                                                    ║
║              │                                                               ║
║              ▼                                                               ║
║       ┌─────────────┐                                                        ║
║       │    emr_db   │                                                        ║
║       └─────────────┘                                                        ║
║                                                                              ║
║                         EVENT-DRIVEN BACKBONE                                ║
║                                                                              ║
║       ┌──────────────────────────────────────────────────────┐               ║
║       │                       KAFKA                          │               ║
║       │                                                      │               ║
║       │       Domain Events / Asynchronous Processing        │               ║
║       └──────────────┬──────────────┬──────────────┬────────┘               ║
║                      │              │              │                         ║
║                      ▼              ▼              ▼                         ║
║              ┌─────────────┐ ┌─────────────┐ ┌──────────────┐                ║
║              │ Notification│ │    Audit    │ │  Analytics   │                ║
║              │   :8087     │ │    :8088    │ │    :8089     │                ║
║              └──────┬──────┘ └─────────────┘ └──────────────┘                ║
║                     │                                                        ║
║                     ▼                                                        ║
║              ┌─────────────┐                                                 ║
║              │  RabbitMQ   │                                                 ║
║              │ Retry / DLQ │                                                 ║
║              └─────────────┘                                                 ║
║                                                                              ║
║                         SHARED INFRASTRUCTURE                                ║
║                                                                              ║
║                 ┌────────────┐       ┌──────────────┐                       ║
║                 │   Redis    │       │    MinIO     │                       ║
║                 │   Cache    │       │ Object Store │                       ║
║                 └────────────┘       └──────────────┘                       ║
║                                                                              ║
╚══════════════════════════════════════════════════════════════════════════════╝

                         EXTERNAL INTEGRATION
                                  │
                                  ▼
                       ┌──────────────────────┐
                       │   Payer / Clearing-  │
                       │       house          │
                       │                      │
                       │   SOAP Eligibility   │
                       └──────────────────────┘
```

---

## 2. External Access Path

The platform exposes a **single external entry point**.

```text
┌─────────────────────┐
│    Client Browser   │
└──────────┬──────────┘
           │
           │ HTTPS
           ▼
┌─────────────────────┐
│     Frontend SPA    │
└──────────┬──────────┘
           │
           │ API Requests
           ▼
┌─────────────────────┐
│    NGINX Ingress    │
│                     │
│ Single Entry Point  │
└──────────┬──────────┘
           │
     ┌─────┼──────────────────────────────────────────────┐
     │     │       │          │             │              │
     ▼     ▼       ▼          ▼             ▼              ▼
  /auth /patients /doctors /appointments /graphql      other
     │     │       │          │             │
     ▼     ▼       ▼          ▼             ▼
 Identity Patient Doctor Appointment GraphQL Gateway
```

The ingress boundary prevents clients from directly addressing internal services.

---

## 3. Application Containers

The platform is composed of independently deployable application containers.

```text
┌───────────────────────────────┬───────────────┬─────────────────────────────┐
│ Container                     │ Port          │ Primary Responsibility      │
├───────────────────────────────┼───────────────┼─────────────────────────────┤
│ identity-service              │ 8081          │ Authentication / RBAC       │
│ patient-service               │ 8082          │ Patient management          │
│ doctor-service                │ 8083          │ Doctors / clinical staff    │
│ appointment-service           │ 8084          │ Scheduling / appointments  │
│ emr-service                   │ 8085          │ Clinical / EMR workflows   │
│ billing-service               │ 8086          │ Billing / eligibility       │
│ notification-service          │ 8087          │ Notifications               │
│ audit-service                 │ 8088          │ Audit events                │
│ analytics-service             │ 8089          │ Event-driven analytics      │
│ graphql-gateway               │ 8090          │ GraphQL BFF                  │
└───────────────────────────────┴───────────────┴─────────────────────────────┘
```

Each Spring Boot service can be built, packaged, deployed, scaled, and restarted independently.

---

## 4. Synchronous Communication

Synchronous communication is used when the caller needs an immediate response.

### GraphQL Gateway

```text
                    ┌──────────────────────┐
                    │   graphql-gateway    │
                    │        :8090         │
                    └──────────┬───────────┘
                               │
                 ┌─────────────┼──────────────┐
                 │             │              │
                 │ gRPC        │ gRPC         │ REST
                 ▼             ▼              ▼
        ┌─────────────┐ ┌─────────────┐ ┌──────────────┐
        │   Patient   │ │   Doctor    │ │ Appointment  │
        │   :8082     │ │   :8083     │ │    :8084     │
        └─────────────┘ └─────────────┘ └──────────────┘
                                             
                               │
                               │ REST
                               ▼
                       ┌──────────────┐
                       │   Billing    │
                       │    :8086     │
                       └──────────────┘
```

### Appointment Service

```text
┌────────────────────────┐
│ appointment-service    │
└───────────┬────────────┘
            │
       ┌────┴─────┐
       │          │
       │ gRPC     │ gRPC
       ▼          ▼
┌────────────┐ ┌────────────┐
│  Patient   │ │   Doctor   │
│  Service   │ │  Service   │
└────────────┘ └────────────┘
```

### EMR Service

```text
┌────────────────────────┐
│     emr-service        │
└───────────┬────────────┘
            │
            │ REST
            ▼
┌────────────────────────┐
│    patient-service     │
└────────────────────────┘
```

---

## 5. Event-Driven Communication

Kafka provides the platform's primary event backbone.

```text
             DOMAIN SERVICES
                   │
          ┌────────┼────────┐
          │        │        │
          ▼        ▼        ▼
     Appointment   EMR    Other
       Service    Service  Services
          │        │
          └────┬───┘
               │
               │ Domain Events
               ▼
        ╔═══════════════════╗
        ║       KAFKA       ║
        ║                   ║
        ║ Event Backbone    ║
        ╚═════════╤═════════╝
                  │
       ┌──────────┼──────────┐
       │          │          │
       ▼          ▼          ▼
┌────────────┐ ┌────────┐ ┌─────────────┐
│Notification│ │ Audit  │ │  Analytics  │
│  Service   │ │Service │ │   Service   │
└────────────┘ └────────┘ └─────────────┘
```

This allows downstream consumers to react to business events without creating direct synchronous dependencies between every service.

For the detailed topic and event model, see [Event Topology](05-event-topology.md).

---

## 6. Messaging Infrastructure

### Kafka

Kafka is used for **domain events and asynchronous service integration**.

```text
┌────────────────────┐
│ Appointment / EMR  │
│ Other Domain       │
│ Services           │
└─────────┬──────────┘
          │
          │ Publish
          ▼
╔════════════════════╗
║       KAFKA        ║
║                    ║
║ Domain Event Bus   ║
╚════════╤═══════════╝
         │
         │ Consume
    ┌────┼─────┬─────────────┐
    ▼    ▼     ▼             ▼
 Billing Notification      Audit      Analytics
```

### RabbitMQ

RabbitMQ is currently focused on notification task processing.

```text
┌────────────────────────┐
│ notification-service   │
└───────────┬────────────┘
            │
            ▼
     ┌──────────────┐
     │   RabbitMQ   │
     │              │
     │ Tasks / Retry│
     └───────┬──────┘
             │
       ┌─────┴─────┐
       │           │
       ▼           ▼
   Processing     DLQ
```

Kafka and RabbitMQ therefore serve different purposes rather than acting as interchangeable messaging systems.

---

## 7. Data Storage

The platform follows a **database-per-service** ownership model.

```text
┌──────────────────────┐
│ identity-service     │──────► identity_db
└──────────────────────┘

┌──────────────────────┐
│ patient-service      │──────► patient_db
└──────────────────────┘

┌──────────────────────┐
│ doctor-service       │──────► doctor_db
└──────────────────────┘

┌──────────────────────┐
│ appointment-service  │──────► appointment_db
└──────────────────────┘

┌──────────────────────┐
│ emr-service          │──────► emr_db
└──────────────────────┘

┌──────────────────────┐
│ billing-service      │──────► billing_db
└──────────────────────┘
```

Services own their data and do not share a single relational schema.

### Redis

Redis is used for read caching by selected services:

```text
        ┌──────────────────┐
        │ patient-service  │───┐
        └──────────────────┘   │
                               ├────► ┌─────────┐
        ┌──────────────────┐   │      │  Redis  │
        │  doctor-service  │───┘      │  Cache  │
        └──────────────────┘          └─────────┘
```

### MinIO

MinIO provides object storage for document-oriented data.

```text
┌────────────────────────┐
│       emr-service      │
└────────────┬───────────┘
             │
             │ Documents
             ▼
      ┌───────────────┐
      │     MinIO     │
      │ Object Store  │
      └───────────────┘
```

---

## 8. Internal-Only Containers

Not every container needs a direct ingress route.

The following services are intentionally **not exposed directly through NGINX Ingress**:

```text
┌────────────────────────┬────────────────────────────────────────────┐
│ Container              │ Access Model                               │
├────────────────────────┼────────────────────────────────────────────┤
│ emr-service            │ Internal REST + Kafka                       │
│ billing-service        │ Internal REST + Kafka + SOAP                │
│ notification-service   │ Kafka + RabbitMQ                            │
│ audit-service          │ Kafka                                      │
│ analytics-service      │ Kafka                                      │
└────────────────────────┴────────────────────────────────────────────┘
```

This is an intentional architecture decision, not an omission.

These services are backend capabilities consumed by other services or by the event backbone rather than directly by the frontend.

See [ADR-0001 — API Gateway / Ingress](../adr/0001-api-gateway-ingress.md).

---

## 9. Container Responsibilities

| Container              | Kind               | Reached Via                  | Primary Responsibility      |
| ---------------------- | ------------------ | ---------------------------- | --------------------------- |
| Frontend               | Static SPA         | Browser / Ingress            | User interface              |
| NGINX Ingress          | Edge router        | External entry point         | Routing and ingress         |
| `identity-service`     | Spring Boot        | Ingress / REST               | Authentication and RBAC     |
| `patient-service`      | Spring Boot        | Ingress / gRPC / REST        | Patient management          |
| `doctor-service`       | Spring Boot        | Ingress / gRPC               | Doctor and staff management |
| `appointment-service`  | Spring Boot        | Ingress / gRPC / Kafka       | Scheduling                  |
| `graphql-gateway`      | Spring Boot BFF    | Ingress / GraphQL            | Client-facing aggregation   |
| `emr-service`          | Spring Boot        | Internal REST / Kafka        | EMR and clinical workflows  |
| `billing-service`      | Spring Boot        | Internal REST / Kafka / SOAP | Billing and eligibility     |
| `notification-service` | Spring Boot        | Kafka / RabbitMQ             | Notification processing     |
| `audit-service`        | Spring Boot        | Kafka                        | Audit event processing      |
| `analytics-service`    | Spring Boot        | Kafka                        | Analytics event processing  |
| Kafka                  | Event backbone     | Domain services              | Asynchronous events         |
| RabbitMQ               | Task / retry queue | `notification-service`       | Notification processing     |
| PostgreSQL             | Datastore          | Owning services              | Persistent relational data  |
| Redis                  | Cache              | Selected services            | Read caching                |
| MinIO                  | Object storage     | EMR / document workflows     | Document storage            |

---

## 10. Architecture Principles

### 10.1 Single External Entry Point

NGINX Ingress provides the external entry point to the backend platform.

### 10.2 Independently Deployable Services

Each Spring Boot service is packaged and deployed independently, allowing individual capabilities to evolve and scale separately.

### 10.3 Database-per-Service

Each domain service owns its persistence boundary rather than sharing a centralized application database.

### 10.4 Synchronous Communication for Immediate Responses

REST and gRPC are used where a service requires an immediate response from another service.

### 10.5 Event-Driven Integration

Kafka decouples domain services from downstream consumers such as notifications, audit, and analytics.

### 10.6 Dedicated Task Messaging

RabbitMQ is used specifically for notification processing, including retry and dead-letter handling.

### 10.7 Internal Services Stay Internal

Services without a direct frontend requirement are not unnecessarily exposed through the external ingress.

---

## 11. Related Architecture Documentation

| Document                                                       | Focus                           |
| -------------------------------------------------------------- | ------------------------------- |
| [System Context](01-system-context.md)                         | Users and external systems      |
| [Container Architecture](02-container-architecture.md)         | Deployable runtime containers   |
| [Service Architecture](03-service-architecture.md)             | Domain responsibilities         |
| [Data Architecture](03-data-architecture.md)                   | Persistence and data ownership  |
| [Communication Architecture](04-communication-architecture.md) | REST, gRPC, Kafka, and RabbitMQ |
| [Event Topology](05-event-topology.md)                         | Kafka topics and event flows    |
| [ADR-0001](../adr/0001-api-gateway-ingress.md)                 | API gateway / ingress decision  |

> **C4 Level 2:** This document answers **what deployable containers make up the platform and how those containers communicate**. Detailed implementation classes, database schemas, Kubernetes resources, and infrastructure configuration belong to lower-level documentation.
