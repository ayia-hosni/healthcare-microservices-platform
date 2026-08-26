# ☁️ AWS Architecture

> Enterprise-oriented cloud architecture for the Healthcare Platform, built around
> Kubernetes, managed cloud services, event-driven communication, workload identity,
> infrastructure as code, and observable cloud-native operations.
>
> Azure is the platform's real, provisioned cloud architecture — see
> [`../azure/README.md`](../azure/README.md). This document defines the equivalent AWS
> architecture, and [`../../../PROGRESS.md`](../../../PROGRESS.md) tracks implementation status
> item by item.
>
> 🏗️ **Designed — not deployed.** There is no AWS Terraform provider or AWS infrastructure in
> this repository. Every diagram and recommendation in this doc and its sub-pages is a target
> design, not a description of running infrastructure — see
> [Architecture Decision Summary](#architecture-decision-summary) for the implementation status
> of each piece.

---

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Architecture Principles](#architecture-principles)
- [Azure → AWS Service Mapping](#azure--aws-service-mapping)
- [Platform Components](#platform-components)
- [Application Architecture](#application-architecture)
- [Explore the Architecture](#explore-the-architecture)
- [Production Evolution](#production-evolution)
- [Deliberately Not Mirrored](#deliberately-not-mirrored)
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
                               │   Route 53 / TLS /  │
                               │        WAF          │
                               └──────────┬─────────┘
                                          │
                                          ▼
                               ┌────────────────────┐
                               │  Application Load   │
                               │  Balancer (ALB) /   │
                               │  Ingress Layer      │
                               └──────────┬─────────┘
                                          │
                                          ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                               Amazon VPC                                     │
│                                                                             │
│   ┌─────────────────────────────────────────────────────────────────────┐   │
│   │                              EKS                                    │   │
│   │                                                                     │   │
│   │  ┌──────────────────┐        ┌───────────────────────────────────┐ │   │
│   │  │ System Node Group│        │       Application Node Group      │ │   │
│   │  │                  │        │                                   │ │   │
│   │  │ CoreDNS          │        │ Identity                          │ │   │
│   │  │ VPC CNI          │        │ Patient                           │ │   │
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
│   │                   OIDC / IAM Roles for Service Accounts             │   │
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
   │ Amazon RDS for   │   │ Amazon           │      │ Amazon MSK       │
   │ PostgreSQL       │   │ ElastiCache      │      │ Kafka-compatible │
   └──────────────────┘   └──────────────────┘      └──────────────────┘
             │
             │
             ▼
   ┌──────────────────┐
   │ Amazon S3        │
   │                  │
   │ EMR Attachments  │
   └──────────────────┘


   ┌──────────────────┐       ┌────────────────────┐
   │ AWS Secrets      │       │ Amazon ECR          │
   │ Manager          │       │                     │
   └──────────────────┘       └────────────────────┘


   ┌────────────────────────────────────────────────────────────────────┐
   │                       Observability                                │
   │                                                                    │
   │  CloudWatch     │ Prometheus │ Grafana │ Zipkin │ Container Insights│
   └────────────────────────────────────────────────────────────────────┘
```

---

# Architecture Principles

## 1. Kubernetes-First Runtime

Application workloads run as containerized services on Amazon Elastic
Kubernetes Service. The application layer remains independent of
AWS-specific compute APIs. See [Compute](compute.md).

---

## 2. Managed Services for Stateful Infrastructure

Managed AWS services are preferred where they significantly reduce
operational overhead.

| Capability                | AWS Service                    |
| ------------------------- | ------------------------------- |
| Kubernetes                | EKS                             |
| PostgreSQL                | Amazon RDS for PostgreSQL       |
| Redis                     | Amazon ElastiCache for Redis    |
| Event streaming           | Amazon MSK                      |
| Secrets                   | AWS Secrets Manager             |
| Container registry        | Amazon ECR                      |
| Object storage            | Amazon S3                       |
| Infrastructure monitoring | Amazon CloudWatch               |

Application-specific infrastructure such as RabbitMQ and Elasticsearch
remains self-hosted inside the cluster rather than moved to a managed
equivalent. See [Data Services](data-services.md).

---

## 3. Database-per-Service

Each business service owns its own logical database, dedicated role, and
credentials. See [Data Services](data-services.md#postgresql--amazon-rds).

---

## 4. Event-Driven Communication

The platform uses asynchronous events for operations that do not require
immediate request/response communication. See
[Messaging — Amazon MSK](data-services.md#messaging--amazon-msk).

---

## 5. Identity-Based Cloud Access

Workloads authenticate using federated workload identity rather than static
AWS credentials. See [Identity & Security](identity-security.md#workload-identity).

---

## 6. Infrastructure as Code

AWS infrastructure is managed through Terraform. See
[Infrastructure as Code](compute.md#infrastructure-as-code).

---

# Azure → AWS Service Mapping

The platform's real cloud deployment is Azure (see
[`../azure/README.md`](../azure/README.md)). The mapping below is
intentionally **architectural rather than product-name driven** — where
there is no strong one-to-one equivalent, this design preserves the
application requirement instead of forcing a managed AWS product into the
architecture.

| Concern                    | Azure — Implemented                           | AWS — This Document                           |
| --------------------------- | --------------------------------------------- | --------------------------------------------- |
| Kubernetes                 | AKS                                           | Amazon EKS                                    |
| Container Registry         | Azure Container Registry                      | Amazon ECR                                    |
| PostgreSQL                 | Azure Database for PostgreSQL Flexible Server | Amazon RDS for PostgreSQL                     |
| Redis                      | Azure Cache for Redis                         | Amazon ElastiCache for Redis                  |
| Kafka-compatible streaming | Azure Event Hubs                              | Amazon MSK                                    |
| Secrets                    | Azure Key Vault                               | AWS Secrets Manager                           |
| Object Storage             | Azure Blob Storage                            | Amazon S3                                     |
| Network                    | Azure VNet                                    | Amazon VPC                                    |
| Kubernetes identity        | Azure Workload Identity                       | EKS OIDC + IAM Roles for Service Accounts     |
| Load Balancing             | NGINX / Azure networking                      | AWS Load Balancer Controller + ALB            |
| TLS                        | Azure-managed certificates                    | AWS Certificate Manager                       |
| DNS                        | Azure DNS                                     | Amazon Route 53                               |
| Logs                       | Log Analytics                                 | CloudWatch Logs                               |
| Container metrics          | Azure Container Insights                      | CloudWatch Container Insights                 |
| Policy enforcement         | Azure Policy                                  | Kyverno / OPA Gatekeeper                      |
| RabbitMQ                   | Self-hosted in Kubernetes                     | Self-hosted in Kubernetes                     |
| Elasticsearch               | Self-hosted in Kubernetes                     | Self-hosted in Kubernetes                     |

---

# Platform Components

| Layer            | Technology                    |
| ----------------- | ------------------------------ |
| Frontend         | Angular                       |
| API Gateway      | GraphQL Gateway               |
| Backend          | Java 21 + Spring Boot         |
| Runtime          | Kubernetes / EKS              |
| Synchronous APIs | REST / gRPC                   |
| Event Streaming  | Amazon MSK                    |
| Messaging        | RabbitMQ                      |
| Database         | PostgreSQL                    |
| Cache            | Redis                         |
| Object Storage   | Amazon S3                     |
| Search           | Elasticsearch                 |
| Metrics          | Prometheus                    |
| Dashboards       | Grafana                       |
| Tracing          | Zipkin                        |
| Logs             | Amazon CloudWatch             |
| Secrets          | AWS Secrets Manager           |
| Registry         | Amazon ECR                    |
| IaC              | Terraform                     |

---

# Application Architecture

The backend is organized around domain-oriented microservices — identical
to the Azure deployment's application layer.

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

---

# Explore the Architecture

| Topic | Document |
| --- | --- |
| Compute — EKS, networking internals, scaling, IaC, CI/CD | [`compute.md`](compute.md) |
| Networking — API/ingress, VPC, egress, network security | [`networking.md`](networking.md) |
| Data Services — RDS, ElastiCache, MSK, RabbitMQ, S3, event reliability | [`data-services.md`](data-services.md) |
| Identity & Security — Secrets Manager, IRSA, defense-in-depth | [`identity-security.md`](identity-security.md) |
| Observability — metrics, logging, tracing | [`observability.md`](observability.md) |

---

# Production Evolution

## Phase 1 — Cloud-Native Foundation (Cost-Constrained POC)

```text
EKS
 │
 ├── One RDS instance (9 databases, 9 roles)
 ├── Minimal ElastiCache
 ├── Minimal MSK cluster
 ├── Secrets Manager
 ├── ECR
 └── S3 (single bucket)
```

This POC topology is a **cost optimization**, not the intended final
production topology — RabbitMQ and Elasticsearch stay self-hosted in this
phase since nothing in the application currently depends on a managed
replacement for either (Elasticsearch specifically has no consumer wired
up yet — see [`../../../PROGRESS.md`](../../../PROGRESS.md)).

## Phase 2 — Production Hardening

```text
                    Route 53 / AWS WAF
                         │
                         ▼
                 Application Load Balancer
                         │
                         ▼
                        EKS
                         │
              ┌──────────┼──────────┐
              ▼          ▼          ▼
           RDS          Redis      MSK
```

Evolution includes private subnets for all data services, automated TLS
via ACM, WAF, Multi-AZ database configuration, secret rotation, stronger
network segmentation, automated backup validation, disaster recovery
testing, and separate RDS instances per service where justified.

## Phase 3 — High Availability

```text
                    Global Traffic
                          │
                          ▼
                     Route 53
                          │
              ┌───────────┴───────────┐
              ▼                       ▼
          Region A                 Region B
              │                       │
             EKS                     EKS
              │                       │
             RDS                     RDS
              │                       │
             MSK                     MSK
```

Multi-region is intentionally out of scope until an explicit availability
requirement drives it — it adds significant complexity around database
replication, Kafka replication, traffic routing, distributed consistency,
secrets, and operational ownership.

---

# Deliberately Not Mirrored

Not every Azure service gets a managed AWS replacement in this design:

* **Amazon OpenSearch** — the platform contains Elasticsearch, but no
  production consumer currently depends on it, so this design does not
  automatically replace it with a managed equivalent. Migrate only when
  Elasticsearch becomes an actual application dependency.
* **Amazon MQ** — RabbitMQ remains self-hosted because the current
  architecture does not justify changing the messaging infrastructure
  model. Revisit if a managed RabbitMQ becomes operationally valuable.

---

# Architecture Decision Summary

| Area                | Architecture                          | Status                |
| -------------------- | -------------------------------------- | ---------------------- |
| Compute             | Amazon EKS                            | 📋 Designed            |
| Containers          | Docker                                | 📋 Designed            |
| Container Registry  | Amazon ECR                            | 📋 Designed            |
| Backend             | Java 21 / Spring Boot                 | ✅ Cloud-independent   |
| API                 | GraphQL + REST / gRPC                 | ✅ Cloud-independent   |
| Database            | PostgreSQL (Amazon RDS)               | 📋 Designed            |
| Database Isolation  | Database-per-service                  | ✅ Cloud-independent   |
| Cache               | Amazon ElastiCache for Redis          | 📋 Designed            |
| Event Streaming     | Amazon MSK                            | 📋 Designed            |
| Queueing            | RabbitMQ (self-hosted)                | 📋 Designed            |
| Object Storage      | Amazon S3                             | 📋 Designed            |
| Search              | Elasticsearch (self-hosted, unused)   | 📋 Designed            |
| Secrets             | AWS Secrets Manager                   | 📋 Designed            |
| Identity            | IAM Roles for Service Accounts (IRSA) | 📋 Designed            |
| Networking          | Amazon VPC + Cilium (optional)        | 📋 Designed            |
| Egress              | NAT Gateway                           | 📋 Designed            |
| Ingress             | AWS Load Balancer Controller + ALB    | 📋 Designed            |
| Metrics             | Prometheus                            | ✅ Cloud-independent   |
| Dashboards          | Grafana                               | ✅ Cloud-independent   |
| Tracing             | Zipkin                                | ✅ Cloud-independent   |
| Infrastructure Logs | Amazon CloudWatch                     | 📋 Designed            |
| IaC                 | Terraform                             | ❌ No AWS provider yet |
| Deployment          | Kubernetes / GitOps                   | 📋 Designed            |
| Cluster Policy      | Kyverno / OPA Gatekeeper              | 📋 Designed            |
| Multi-region DR     | Route 53 + cross-region replication   | 📋 Planned             |

**Status legend:** ✅ cloud-independent (already true on Azure and carries over unchanged) ·
📋 designed here, not yet implemented as AWS infrastructure · ❌ no implementation exists in
any form. Nothing in this table should be read as deployed — see the banner at the top of this
document and [`../../../PROGRESS.md`](../../../PROGRESS.md) for the platform's actual
implementation status.

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
                           AWS Cloud
```

This is the same application architecture mapped from Azure — the business
domains, service boundaries, and event contracts don't change; only the
infrastructure layer underneath them does. AWS becomes an **infrastructure
implementation of the same platform**, not a reason to redesign the
business architecture. See [`../azure/README.md`](../azure/README.md) for
the currently-real deployment this document mirrors, and
[`../../../PROGRESS.md`](../../../PROGRESS.md) for an itemized view of
what's running today versus still ahead.
