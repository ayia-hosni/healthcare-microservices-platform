# ☁️ Azure Architecture

> Enterprise-oriented cloud architecture for the Healthcare Platform, built around
> Kubernetes, managed cloud services, event-driven communication, workload identity,
> infrastructure as code, and observable cloud-native operations.
>
> Azure provides the platform's primary, real, provisioned cloud architecture (`infra/terraform/`),
> while [`../aws/README.md`](../aws/README.md) defines the equivalent — designed, not built — AWS
> architecture, and [`../../../PROGRESS.md`](../../../PROGRESS.md) tracks implementation status
> item by item.

---

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Architecture Principles](#architecture-principles)
- [Platform Components](#platform-components)
- [Application Architecture](#application-architecture)
- [Explore the Architecture](#explore-the-architecture)
- [Production Evolution](#production-evolution)
- [Architecture Decision Summary](#architecture-decision-summary)
- [Design Philosophy](#design-philosophy)

---

# Architecture Overview

The platform is a cloud-native healthcare system composed of independently
deployable Spring Boot microservices running on Kubernetes.

The architecture separates:

- API traffic
- business services
- persistence
- caching
- asynchronous messaging
- object storage
- identity and secrets
- observability
- infrastructure management

```text
                                      Internet
                                          │
                                          ▼
                               ┌────────────────────┐
                               │    DNS / TLS / WAF │
                               └──────────┬─────────┘
                                          │
                                          ▼
                               ┌────────────────────┐
                               │   Azure Load       │
                               │   Balancer /       │
                               │   Ingress Layer    │
                               └──────────┬─────────┘
                                          │
                                          ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Azure VNet                                      │
│                                                                             │
│   ┌─────────────────────────────────────────────────────────────────────┐   │
│   │                              AKS                                    │   │
│   │                                                                     │   │
│   │  ┌──────────────────┐        ┌───────────────────────────────────┐ │   │
│   │  │ System Node Pool │        │       Application Node Pool       │ │   │
│   │  │                  │        │                                   │ │   │
│   │  │ CoreDNS          │        │ Identity                          │ │   │
│   │  │ CNI              │        │ Patient                           │ │   │
│   │  │ Cluster Add-ons  │        │ Doctor                            │ │   │
│   │  │                  │        │ Appointment                       │ │   │
│   │  └──────────────────┘        │ EMR                               │ │   │
│   │                              │ Billing                            │ │   │
│   │                              │ Notification                       │ │   │
│   │                              │ Audit                              │ │   │
│   │                              │ Analytics                          │ │   │
│   │                              │ GraphQL Gateway                    │ │   │
│   │                              │ RabbitMQ                           │ │   │
│   │                              │ Elasticsearch                      │ │   │
│   │                              └───────────────────────────────────┘ │   │
│   │                                                                     │   │
│   │                   OIDC / Workload Identity                          │   │
│   └──────────────────────────────┬──────────────────────────────────────┘   │
│                                  │                                          │
│                                  ▼                                          │
│                            NAT Gateway                                      │
│                                  │                                          │
└──────────────────────────────────┼──────────────────────────────────────────┘
                                   │
             ┌─────────────────────┼──────────────────────────┐
             │                     │                          │
             ▼                     ▼                          ▼
   ┌──────────────────┐   ┌──────────────────┐      ┌──────────────────┐
   │ Azure PostgreSQL │   │ Azure Cache      │      │ Azure Event Hubs │
   │ Flexible Server  │   │ for Redis        │      │ Kafka-compatible │
   └──────────────────┘   └──────────────────┘      └──────────────────┘
             │
             │
             ▼
   ┌──────────────────┐
   │ Azure Blob       │
   │ Storage          │
   │ EMR Attachments  │
   └──────────────────┘


   ┌──────────────────┐       ┌────────────────────┐
   │ Azure Key Vault  │       │ Azure Container     │
   │                  │       │ Registry (ACR)      │
   └──────────────────┘       └────────────────────┘


   ┌────────────────────────────────────────────────────────────────────┐
   │                       Observability                                │
   │                                                                    │
   │  Log Analytics │ Prometheus │ Grafana │ Zipkin │ Container Metrics │
   └────────────────────────────────────────────────────────────────────┘
```

---

# Architecture Principles

## 1. Kubernetes-First Runtime

Application workloads run as containerized services on Azure Kubernetes
Service. Kubernetes provides service discovery, deployment management,
rolling updates, health checks, horizontal scaling, resource isolation,
workload scheduling, and self-healing. The application layer remains
independent of Azure-specific compute APIs. See [Compute](compute.md).

---

## 2. Managed Services for Stateful Infrastructure

Managed Azure services are preferred where they significantly reduce
operational overhead.

| Capability                | Azure Service                 |
| ------------------------- | ------------------------------ |
| Kubernetes                | AKS                           |
| PostgreSQL                | Azure Database for PostgreSQL |
| Redis                     | Azure Cache for Redis         |
| Event streaming           | Azure Event Hubs              |
| Secrets                   | Azure Key Vault               |
| Container registry        | Azure Container Registry      |
| Object storage            | Azure Blob Storage            |
| Infrastructure monitoring | Azure Monitor / Log Analytics |

This keeps Kubernetes focused on application workloads rather than turning
the cluster into a general-purpose infrastructure platform. See
[Data Services](data-services.md).

---

## 3. Database-per-Service

Each business service owns its own logical database, dedicated role, and
credentials — no direct cross-service queries, only APIs or domain events.
See [Data Services](data-services.md#postgresql).

---

## 4. Event-Driven Communication

The platform uses asynchronous events for operations that do not require
immediate request/response communication, reducing coupling and letting
consumers scale independently. See
[Messaging — Azure Event Hubs](data-services.md#messaging--azure-event-hubs).

---

## 5. Identity-Based Cloud Access

Workloads authenticate using federated workload identity rather than static
Azure credentials, so the application does not need long-lived cloud access
keys inside containers. See
[Identity & Security](identity-security.md#workload-identity).

---

## 6. Infrastructure as Code

Azure infrastructure is managed through Terraform, version-controlled
alongside the application. See
[Infrastructure as Code](compute.md#infrastructure-as-code).

---

# Platform Components

| Layer            | Technology                    |
| ----------------- | ------------------------------ |
| Frontend         | Angular                       |
| API Gateway      | GraphQL Gateway               |
| Backend          | Java 21 + Spring Boot         |
| Runtime          | Kubernetes / AKS              |
| Synchronous APIs | REST / gRPC                   |
| Event Streaming  | Azure Event Hubs              |
| Messaging        | RabbitMQ                      |
| Database         | PostgreSQL                    |
| Cache            | Redis                         |
| Object Storage   | Azure Blob Storage            |
| Search           | Elasticsearch                 |
| Metrics          | Prometheus                    |
| Dashboards       | Grafana                       |
| Tracing          | Zipkin                        |
| Logs             | Azure Monitor / Log Analytics |
| Secrets          | Azure Key Vault               |
| Registry         | Azure Container Registry      |
| IaC              | Terraform                     |

---

# Application Architecture

The backend is organized around domain-oriented microservices.

```text
                         GraphQL Gateway
                                │
              ┌─────────────────┼─────────────────┐
              │                 │                 │
              ▼                 ▼                 ▼
        Identity Service   Patient Service   Doctor Service
              │                 │                 │
              └─────────────────┼─────────────────┘
                                │
                                ▼
                       Appointment Service
                                │
                ┌───────────────┼───────────────┐
                ▼               ▼               ▼
            EMR Service    Billing Service   Notification
                │               │               │
                └───────────────┼───────────────┘
                                ▼
                         Audit / Analytics
```

Each service owns its business logic, persistence, configuration, API
contracts, domain events, and observability — this prevents the backend
from becoming a distributed monolith.

---

# Explore the Architecture

| Topic | Document |
| --- | --- |
| Compute — AKS, networking internals, scaling, IaC, CI/CD | [`compute.md`](compute.md) |
| Networking — API/ingress, VNet, egress, network security | [`networking.md`](networking.md) |
| Data Services — PostgreSQL, Redis, Event Hubs, RabbitMQ, Blob Storage, event reliability | [`data-services.md`](data-services.md) |
| Identity & Security — Key Vault, workload identity, defense-in-depth | [`identity-security.md`](identity-security.md) |
| Observability — metrics, logging, tracing | [`observability.md`](observability.md) |

---

# Production Evolution

The architecture is designed to evolve without requiring a rewrite of the
application layer.

## Phase 1 — Cloud-Native Foundation

```text
AKS
 │
 ├── Managed PostgreSQL
 ├── Managed Redis
 ├── Event Hubs
 ├── Key Vault
 ├── ACR
 └── Blob Storage
```

Focus: service boundaries, containerization, infrastructure as code,
managed stateful services, observability, workload identity.

## Phase 2 — Production Hardening

```text
                Azure Front Door / WAF
                         │
                         ▼
                 Application Gateway
                         │
                         ▼
                        AKS
                         │
              ┌──────────┼──────────┐
              ▼          ▼          ▼
           PostgreSQL  Redis    Event Hubs
```

Evolution includes private endpoints, automated TLS, WAF, zone redundancy,
HA database configuration, secret rotation, stronger network segmentation,
automated backup validation, and disaster recovery testing.

## Phase 3 — High Availability

```text
                    Global Traffic
                          │
                          ▼
                 Azure Front Door
                          │
              ┌───────────┴───────────┐
              ▼                       ▼
          Region A                 Region B
              │                       │
             AKS                     AKS
              │                       │
          PostgreSQL              PostgreSQL
              │                       │
           Event Hubs              Event Hubs
```

At higher scale, the architecture can evolve toward multi-zone AKS,
multi-region deployment, geo-replicated databases, global traffic
management, regional failover, cross-region event replication, and
disaster recovery automation.

---

# Architecture Decision Summary

| Area                | Architecture                             |
| -------------------- | ------------------------------------------ |
| Compute             | Azure Kubernetes Service                 |
| Containers          | Docker                                   |
| Container Registry  | Azure Container Registry                 |
| Backend             | Java 21 / Spring Boot                    |
| API                 | GraphQL + REST / gRPC                    |
| Database            | PostgreSQL                               |
| Database Isolation  | Database-per-service                     |
| Cache               | Azure Cache for Redis                    |
| Event Streaming     | Azure Event Hubs                         |
| Queueing            | RabbitMQ                                 |
| Object Storage      | Azure Blob Storage                       |
| Search              | Elasticsearch                            |
| Secrets             | Azure Key Vault                          |
| Identity            | Azure Workload Identity                  |
| Networking          | Azure VNet + Cilium                      |
| Egress              | NAT Gateway                              |
| Ingress             | Kubernetes Ingress / Azure Edge Services |
| Metrics             | Prometheus                               |
| Dashboards          | Grafana                                  |
| Tracing             | Zipkin                                   |
| Infrastructure Logs | Azure Monitor / Log Analytics            |
| IaC                 | Terraform                                |
| Deployment          | Kubernetes / GitOps                      |
| Cluster Policy      | Azure Policy + Kubernetes Policies       |

---

# Design Philosophy

The platform follows a simple architectural principle:

> **Use Kubernetes for application orchestration, managed cloud services for
> stateful infrastructure, events for loose coupling, workload identity for
> secure access, and infrastructure as code for reproducibility.**

The architecture deliberately separates **business architecture** from
**cloud implementation**.

```text
                         Business Domains
                               │
                               ▼
                       Spring Boot Services
                               │
              ┌────────────────┼────────────────┐
              ▼                ▼                ▼
          PostgreSQL          Events           APIs
              │                │                │
              └────────────────┼────────────────┘
                               │
                               ▼
                         Kubernetes
                               │
                               ▼
                           Azure Cloud
```

This allows the same application architecture to be mapped to another cloud
provider without redesigning the business domains — see
[`../aws/README.md`](../aws/README.md) for the AWS equivalent, and
[`../../../PROGRESS.md`](../../../PROGRESS.md) for an itemized view of
what's running today versus still ahead.
