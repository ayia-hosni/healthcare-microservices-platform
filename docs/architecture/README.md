# 🏗️ Architecture

> How the platform is put together: service boundaries, how they talk to each other, the core
> booking workflow, and how data is owned. For reliability mechanisms (outbox, rate limiting,
> auth, storage tech), see [`../reliability/README.md`](../reliability/README.md). For how to
> run and deploy it, see [`../operations/README.md`](../operations/README.md). For
> implementation status, see [`../../PROGRESS.md`](../../PROGRESS.md).

This page is a short index. The detailed, C4-style deep-dive series lives in
[`00-index.md`](00-index.md) through [`10-appointment-booking-sequence.md`](10-appointment-booking-sequence.md) —
the pages linked below summarize each concern and point into that series rather than
duplicating it.

---

## Table of Contents

* [Architecture Overview](#architecture-overview)
* [Technology Stack](#technology-stack)
* [Shared Modules](#shared-modules)
* [Architecture Documentation](#architecture-documentation)
* [Architecture Decision Records](#architecture-decision-records)

---

## Architecture Overview

The platform separates **external API traffic**, **internal synchronous communication**, and
**asynchronous domain events**.

```text
                                     ┌──────────────────────┐
                                     │      Frontend         │
                                     │     Angular SPA       │
                                     └──────────┬────────────┘
                                                │ HTTPS
                                                ▼
                                     ┌──────────────────────┐
                                     │    NGINX Ingress     │
                                     │  External API Entry  │
                                     └──────────┬────────────┘
                                                │
          ┌──────────────────┬──────────────────┼──────────────────┬───────────────────┐
          │                  │                  │                  │                   │
          ▼                  ▼                  ▼                  ▼                   ▼
   /api/v1/auth      /api/v1/patients   /api/v1/doctors  /api/v1/appointments   /graphql, /graphiql
          │                  │                  │                  │                   │
          ▼                  ▼                  ▼                  ▼                   ▼
 ┌────────────────┐ ┌────────────────┐ ┌────────────────┐ ┌────────────────┐ ┌────────────────────┐
 │    Identity    │ │    Patient     │ │     Doctor     │ │  Appointment   │ │   GraphQL Gateway   │
 │    Service     │ │    Service     │ │    Service     │ │    Service     │ │        (BFF)        │
 │     :8081      │ │     :8082      │ │     :8083      │ │     :8084      │ │        :8090        │
 └───────┬────────┘ └───────┬────────┘ └───────┬────────┘ └───────┬────────┘ └──────────┬───────────┘
         │                  │                  │                  │                     │
         ▼                  ▼                  ▼                  ▼                     │  no database —
  ┌──────────────┐   ┌──────────────┐   ┌──────────────┐   ┌──────────────┐              │  every field is
  │ identity_db  │   │  patient_db  │   │  doctor_db   │   │appointment_db│              │  fetched live over
  │ PostgreSQL   │   │ PostgreSQL   │   │ PostgreSQL   │   │ PostgreSQL   │              │  gRPC or REST
  └──────────────┘   └──────────────┘   └──────────────┘   └──────────────┘              │


    ─────────────────────────────────────────────────────────────────────────────────────
                          INTERNAL-ONLY SERVICES (no Ingress route)
    ─────────────────────────────────────────────────────────────────────────────────────

 ┌────────────────┐ ┌────────────────┐ ┌────────────────┐ ┌────────────────┐ ┌────────────────┐
 │  EMR Service    │ │ Billing Service│ │Notification Svc│ │  Audit Service │ │Analytics Service│
 │     :8085       │ │     :8086      │ │     :8087      │ │     :8088      │ │     :8089       │
 └────────┬────────┘ └───────┬────────┘ └───────┬────────┘ └───────┬────────┘ └────────┬────────┘
          ▼                  ▼                  ▼                  ▼                   ▼
   ┌──────────────┐  ┌──────────────┐  ┌────────────────┐ ┌──────────────┐  ┌───────────────┐
   │    emr_db    │  │  billing_db  │  │ notification_db│ │   audit_db   │  │  analytics_db  │
   │  PostgreSQL  │  │  PostgreSQL  │  │   PostgreSQL   │ │  PostgreSQL  │  │  PostgreSQL    │
   └──────────────┘  └──────────────┘  └────────────────┘ └──────────────┘  └───────────────┘

`emr-service`, `billing-service`, `notification-service`, `audit-service`, and `analytics-service`
are intentionally internal-only and expose no Ingress route today — they are reached through
gRPC/REST fan-out from other services or through Kafka event consumption (see ADR-0001).


    ─────────────────────────────────────────────────────────────────────────────────────
                              ASYNCHRONOUS EVENT LAYER
    ─────────────────────────────────────────────────────────────────────────────────────

                          ┌─────────────────────────┐
                          │   Appointment Service    │
                          └────────────┬─────────────┘
                                       │ AppointmentCreated / AppointmentCancelled
                                       │ (topic: appointment.events, via Outbox)
                                       ▼
                              ┌───────────────────┐
                              │   Apache Kafka     │
                              │   Event Stream     │
                              └─────────┬──────────┘
                                        │
              ┌─────────────────────────┼────────────────────────┐
              │                         │                        │
              ▼                         ▼                        ▼
     ┌─────────────────┐      ┌─────────────────┐       ┌──────────────────┐
     │ Billing Service  │      │  Audit Service   │       │Analytics Service │
     └────────┬─────────┘      └──────────────────┘       └──────────────────┘
              │ InvoiceGenerated / PaymentCompleted
              ▼      (topic: billing.events, back onto Kafka via its own Outbox)
            Kafka

   appointment-service (hourly AppointmentReminderJob, ~24h before a confirmed appointment)
   and emr-service both also publish onto Kafka topic notification.requests, consumed only
   by notification-service — which then hands delivery off to RabbitMQ:

                              ┌──────────────────┐
                              │Notification Svc   │──► RabbitMQ ──► Retry / DLQ
                              │      :8087        │              └─► Email / SMS / Push
                              └───────────────────┘

   `identity-service` touches neither Kafka nor RabbitMQ — it has no domain events today.
   RabbitMQ is used only inside notification-service; it is not a shared bus (ADR-0002).


    ─────────────────────────────────────────────────────────────────────────────────────
                               SHARED INFRASTRUCTURE
    ─────────────────────────────────────────────────────────────────────────────────────

       ┌───────────┐          ┌───────────────┐          ┌────────────────┐
       │   Redis    │          │ Elasticsearch │          │      MinIO      │
       │   Cache    │          │  Provisioned; │          │ Object Storage  │
       └─────▲──────┘          │  no indexing  │          └────────▲────────┘
             │                 │ consumers yet │                   │
   patient-service,            └───────────────┘             emr-service
   doctor-service                                        (clinical documents)


    ─────────────────────────────────────────────────────────────────────────────────────
                                 OBSERVABILITY
    ─────────────────────────────────────────────────────────────────────────────────────

       Spring Boot Services ──► Prometheus ──► Grafana
                            ──► Zipkin (tracing)
                            ──► Actuator ──► Kubernetes liveness / readiness
```

---

## Technology Stack

| Layer            | Technology                    |
| ----------------- | ------------------------------ |
| Frontend         | Angular                       |
| API Gateway      | GraphQL Gateway               |
| Backend          | Java 21 + Spring Boot         |
| Runtime          | Kubernetes                    |
| Synchronous APIs | REST / gRPC                   |
| Event Streaming  | Kafka-compatible (Azure Event Hubs on the real deployment) |
| Messaging        | RabbitMQ                      |
| Database         | PostgreSQL (database-per-service) |
| Cache            | Redis                         |
| Object Storage   | MinIO locally / cloud object storage in production |
| Search           | Elasticsearch (provisioned, not yet wired up — see [`../../PROGRESS.md`](../../PROGRESS.md)) |
| Metrics          | Prometheus                    |
| Dashboards       | Grafana                       |
| Tracing          | Zipkin                        |
| IaC              | Terraform                     |
| GitOps           | Argo CD                       |

---

## Shared Modules

The platform separates reusable infrastructure from wire-level service contracts.

```text
                         ┌───────────────────┐
                         │   grpc-contracts   │
                         │                    │
                         │  .proto contracts  │
                         │  Generated stubs   │
                         │  Message classes   │
                         └─────────┬──────────┘
                                   │
                    ┌──────────────┴──────────────┐
                    │                             │
                    ▼                             ▼
             ┌──────────────┐              GraphQL Gateway
             │    common    │              (depends on grpc-contracts
             │              │               only — not on `common`)
             │ dto          │
             │ outbox       │
             │ cache        │
             │ security     │
             │ web          │
             │ events       │
             │ exception    │
             └──────┬───────┘
                    │
                    ▼
        The other nine Spring services
```

`common` provides the shared `OutboxWriter`/`OutboxEventRepository`/`OutboxRelay`, JWT
verification, Redis cache config, DTOs, domain events, and exception handling used across
services. `grpc-contracts` is split out separately so `graphql-gateway` — which deliberately
doesn't depend on `common` (see its `pom.xml`) — can pull in just the wire contracts without
inheriting `common`'s JPA/security/web dependencies. Both are library modules and are not
independently deployed.

---

## Architecture Documentation

| Topic                 | Document                                                | Deep dive |
| --------------------- | -------------------------------------------------------- | --- |
| Application Structure | [Application Architecture](application-architecture.md) | [02-container-architecture.md](02-container-architecture.md) |
| Services              | [Microservices](microservices.md)                       | [03-service-architecture.md](03-service-architecture.md) |
| Supporting Services   | [Supporting Services](supporting-services.md)             | — |
| Communication         | [Service Communication](communication.md)               | [04-communication-architecture.md](04-communication-architecture.md), [05-event-topology.md](05-event-topology.md) |
| Data                  | [Data Architecture](data-architecture.md)                | [06-data-architecture.md](06-data-architecture.md) |
| System Context        | —                                                        | [01-system-context.md](01-system-context.md) |
| Security              | See [`../cloud/azure/identity-security.md`](../cloud/azure/identity-security.md) | [07-security-architecture.md](07-security-architecture.md) |
| Deployment            | —                                                        | [08-deployment-architecture.md](08-deployment-architecture.md) |
| Observability         | [Operations: Observability](../operations/observability.md) | [09-observability-architecture.md](09-observability-architecture.md) |
| Appointment Booking Sequence | —                                                  | [10-appointment-booking-sequence.md](10-appointment-booking-sequence.md) |
| Reliability & Security | [`../reliability/README.md`](../reliability/README.md) | — |
| Cloud                 | [Cloud Architecture](../cloud/README.md)                 | — |

---

## Architecture Decision Records

Architectural decisions are documented under [`../adr/`](../adr/):

* [ADR-0001 — API Gateway / Ingress](../adr/0001-api-gateway-ingress.md)
* [ADR-0002 — Messaging Topology](../adr/0002-messaging-topology.md)
* [ADR-0003 — SOAP Payer Eligibility Integration](../adr/0003-soap-payer-eligibility-integration.md)
* [ADR-0004 — gRPC for Internal Synchronous Calls](../adr/0004-grpc-for-internal-synchronous-calls.md)

Each ADR documents the context, alternatives, decision, and consequences behind an
architectural choice.
