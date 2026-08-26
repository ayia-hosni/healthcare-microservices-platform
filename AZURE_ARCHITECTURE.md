# ☁️ Azure Architecture

> Enterprise-oriented cloud architecture for the Healthcare Platform, built around
> Kubernetes, managed cloud services, event-driven communication, workload identity,
> infrastructure as code, and observable cloud-native operations.
>
> Azure provides the platform's primary cloud architecture, while
> [`AWS_ARCHITECTURE.md`](AWS_ARCHITECTURE.md) defines the equivalent AWS architecture, and
> [`PROGRESS.md`](PROGRESS.md) tracks implementation status item by item.

---

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Architecture Principles](#architecture-principles)
- [Platform Components](#platform-components)
- [Compute — AKS](#compute--aks)
- [Application Architecture](#application-architecture)
- [API & Ingress](#api--ingress)
- [Data Architecture](#data-architecture)
- [Caching — Azure Cache for Redis](#caching--azure-cache-for-redis)
- [Messaging — Azure Event Hubs](#messaging--azure-event-hubs)
- [RabbitMQ](#rabbitmq)
- [Object Storage — Azure Blob Storage](#object-storage--azure-blob-storage)
- [Secrets & Identity](#secrets--identity)
- [Networking](#networking)
- [Security Architecture](#security-architecture)
- [Observability](#observability)
- [Availability & Scaling](#availability--scaling)
- [Infrastructure as Code](#infrastructure-as-code)
- [CI/CD & Deployment](#cicd--deployment)
- [Data & Event Reliability](#data--event-reliability)
- [Production Evolution](#production-evolution)
- [Architecture Decision Summary](#architecture-decision-summary)

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
Service.

Kubernetes provides:

* service discovery
* deployment management
* rolling updates
* health checks
* horizontal scaling
* resource isolation
* workload scheduling
* self-healing

The application layer remains independent of Azure-specific compute APIs.

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
the cluster into a general-purpose infrastructure platform.

---

## 3. Database-per-Service

Each business service owns its own logical database.

```text
PostgreSQL
│
├── identity_db
├── patient_db
├── doctor_db
├── appointment_db
├── emr_db
├── billing_db
├── notification_db
├── audit_db
└── analytics_db
```

Each database has:

* a dedicated database
* a dedicated login role
* dedicated credentials
* an explicit ownership boundary

Services do not directly query another service's database.

Cross-service information is exchanged through APIs or domain events.

---

## 4. Event-Driven Communication

The platform uses asynchronous events for operations that do not require
immediate request/response communication.

```text
Appointment Service
        │
        │ appointment.events
        ▼
   Azure Event Hubs
        │
        ├──────────────► Notification
        │
        ├──────────────► Billing
        │
        ├──────────────► Analytics
        │
        └──────────────► Audit
```

This reduces coupling between services and allows consumers to scale
independently.

---

## 5. Identity-Based Cloud Access

Workloads authenticate using federated workload identity rather than static
Azure credentials.

```text
Kubernetes ServiceAccount
            │
            ▼
       AKS OIDC Issuer
            │
            ▼
   Federated Identity Credential
            │
            ▼
   Azure Managed Identity
            │
            ▼
      Azure Resources
```

The application does not need long-lived cloud access keys inside containers.

---

## 6. Infrastructure as Code

Azure infrastructure is managed through Terraform.

```text
Terraform
    │
    ▼
Terraform Plan
    │
    ▼
Code Review
    │
    ▼
Terraform Apply
    │
    ▼
Azure Infrastructure
```

Infrastructure configuration is version-controlled alongside the application.

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

# Compute — AKS

Azure Kubernetes Service provides the application runtime.

## Cluster Layout

```text
AKS Cluster
│
├── System Node Pool
│   ├── CoreDNS
│   ├── CNI
│   ├── Metrics components
│   └── Kubernetes add-ons
│
└── Application Node Pool
    ├── Identity Service
    ├── Patient Service
    ├── Doctor Service
    ├── Appointment Service
    ├── EMR Service
    ├── Billing Service
    ├── Notification Service
    ├── Audit Service
    ├── Analytics Service
    └── GraphQL Gateway
```

Application infrastructure such as RabbitMQ and Elasticsearch can run as
cluster workloads while managed Azure services handle the core persistent
platform dependencies.

---

## Azure CNI Overlay

The cluster uses Azure CNI Overlay networking.

```text
Azure VNet
10.20.0.0/16
      │
      └── AKS Subnet
           10.20.0.0/20

Pod Network
10.244.0.0/16
```

This separates pod addressing from the VNet subnet and reduces the amount
of VNet address space required for Kubernetes workloads.

---

## Cilium

Cilium provides Kubernetes networking and network policy enforcement.

```text
Pod A
 │
 │ Network Policy
 ▼
Cilium
 │
 ├── Allow
 └── Deny
       │
       ▼
     Pod B
```

This allows service-to-service traffic to be explicitly controlled rather
than relying only on application-level authorization.

---

## Node Pool Strategy

The platform separates infrastructure workloads from application workloads.

Application workloads can use:

* node selectors
* labels
* taints
* tolerations
* pod affinity
* pod anti-affinity
* resource requests
* resource limits

This allows individual workload classes to evolve independently.

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

Each service owns:

* business logic
* persistence
* configuration
* API contracts
* domain events
* observability

This prevents the backend from becoming a distributed monolith.

---

# API & Ingress

The API layer provides a single external entry point into the platform.

```text
Client
  │
  ▼
DNS
  │
  ▼
TLS
  │
  ▼
WAF / Load Balancer
  │
  ▼
Ingress Controller
  │
  ▼
GraphQL / API Gateway
  │
  ├── Identity
  ├── Patient
  ├── Doctor
  ├── Appointment
  ├── EMR
  ├── Billing
  └── Notification
```

Ingress is responsible for:

* HTTP routing
* TLS termination
* request forwarding
* health-aware routing
* centralized edge policies

Application services remain unaware of the external load-balancing
implementation. The routing rules themselves — one path per service — are
already defined declaratively in `infra/k8s/base/ingress.yaml`, the same
manifest used against Minikube's `ingress-nginx` addon in local development;
standing up an ingress controller and DNS/TLS on the AKS cluster is what
turns that routing table from a config file into a reachable public edge.

### Production Edge

A production deployment can use:

```text
Internet
   │
   ▼
Azure DNS
   │
   ▼
Azure WAF
   │
   ▼
Application Gateway / Load Balancer
   │
   ▼
AKS Ingress
```

Certificates should be automatically issued and renewed rather than
managed manually.

---

# Data Architecture

## PostgreSQL

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

The application follows a database-per-service ownership model.

---

## Transaction Boundaries

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
transactions.

This avoids coupling services through two-phase commit.

---

## Production Database Evolution

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
isolation can increase with workload requirements.

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

Cache entries are disposable.

PostgreSQL remains the authoritative source.

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

---

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

---

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

# Secrets & Identity

Azure Key Vault is the central secret-management system.

## Secret Flow

```text
                         Azure Key Vault
                               │
               ┌───────────────┼───────────────┐
               ▼               ▼               ▼
          JWT Secret      DB Credentials    Service Secrets
               │               │               │
               └───────────────┼───────────────┘
                               │
                               ▼
                     Workload Identity
                               │
                               ▼
                              AKS
```

---

## Workload Identity

Pods authenticate through the AKS OIDC issuer.

```text
Pod
 │
 ▼
Kubernetes ServiceAccount
 │
 ▼
OIDC Token
 │
 ▼
Federated Credential
 │
 ▼
Azure Managed Identity
 │
 ▼
Key Vault / Azure APIs
```

This eliminates long-lived cloud credentials from application containers.

---

## Least Privilege

Identity permissions should be scoped to the workload.

```text
identity-service
      │
      └── identity-related secrets

emr-service
      │
      └── storage-related permissions

analytics-service
      │
      └── analytics resources
```

The same principle applies to storage, messaging, and cloud APIs.

---

# Networking

The Azure network is organized around private application infrastructure
and controlled external access.

```text
                         Internet
                            │
                            ▼
                     Public Edge
                            │
                            ▼
                       Azure VNet
                            │
                     ┌──────┴──────┐
                     │             │
                     ▼             ▼
                AKS Subnet     Public Edge
                     │
                     ▼
               Application Pods
                     │
                     ▼
                NAT Gateway
                     │
          ┌──────────┼──────────┐
          ▼          ▼          ▼
       PostgreSQL  Redis    Event Hubs
```

---

## NAT Gateway

The NAT Gateway provides stable outbound connectivity.

```text
AKS Pod
   │
   ▼
AKS Subnet
   │
   ▼
NAT Gateway
   │
   ▼
Static Public IP
   │
   ▼
Azure Services
```

The static egress address can be used for controlled firewall allow-lists
and external integrations.

---

## Network Security

The architecture combines:

* VNet isolation
* NSGs
* Kubernetes network policies
* controlled ingress
* controlled egress
* firewall rules
* managed identity

The goal is to establish explicit trust boundaries between application,
platform, and external resources.

---

# Security Architecture

Security follows defense-in-depth.

```text
                        Internet
                           │
                           ▼
                     WAF / TLS
                           │
                           ▼
                       Ingress
                           │
                           ▼
                 Kubernetes Network Policy
                           │
                           ▼
                   Service Authorization
                           │
                           ▼
                   Workload Identity
                           │
                           ▼
                   Database Authorization
```

## Security Controls

### Identity

* Azure AD / Entra ID
* AKS RBAC
* Workload Identity
* Managed identities
* Least-privilege permissions

### Network

* VNet isolation
* NSGs
* Cilium network policies
* controlled egress
* firewall allow-lists
* TLS

### Secrets

* Azure Key Vault
* no credentials in source code
* no cloud credentials in container images
* secret rotation strategy

### Containers

* non-root containers
* minimal images
* image scanning
* immutable image tags
* resource limits

### Kubernetes

* RBAC
* network policies
* pod security controls
* namespace isolation
* workload identities

---

# Observability

The platform uses a layered observability architecture.

```text
                    Observability
                         │
          ┌──────────────┼──────────────┐
          ▼              ▼              ▼
        Logs          Metrics         Traces
          │              │              │
          ▼              ▼              ▼
    Log Analytics    Prometheus       Zipkin
                         │
                         ▼
                      Grafana
```

---

## Metrics

Prometheus collects application and infrastructure metrics such as:

* HTTP request rate
* HTTP latency
* error rate
* JVM memory
* JVM threads
* database connections
* Kafka consumer lag
* Redis usage
* Kubernetes resource utilization

---

## Logging

Centralized logging provides:

* structured application logs
* Kubernetes logs
* infrastructure logs
* searchable incident history
* correlation identifiers

Logs should use structured JSON where possible.

---

## Distributed Tracing

Tracing follows requests across service boundaries.

```text
Frontend
   │
   ▼
GraphQL Gateway
   │
   ▼
Appointment Service
   │
   ├──────────► Patient Service
   │
   ├──────────► Billing Service
   │
   └──────────► Notification Service
```

Correlation IDs connect:

```text
HTTP Request
     │
     ├── Logs
     ├── Trace
     └── Events
```

This makes distributed incident investigation significantly easier.

---

# Availability & Scaling

The platform uses multiple layers of scaling.

```text
Incoming Traffic
       │
       ▼
Load Balancer
       │
       ▼
Ingress
       │
       ▼
Horizontal Pod Autoscaler
       │
       ▼
Kubernetes Pods
       │
       ▼
Cluster Autoscaler
       │
       ▼
AKS Nodes
```

---

## Application Scaling

Services can scale independently.

```text
Appointment Service
      2 replicas
           │
           ▼
        10 replicas
```

while another service may remain at:

```text
Audit Service
      2 replicas
```

This prevents the scaling characteristics of one domain from dictating the
resource requirements of the entire platform.

---

## Database Scaling

Database scaling can evolve independently:

```text
Primary
  │
  ├── Read Replica
  │
  ├── Connection Pool
  │
  └── Service-specific optimization
```

---

# Data & Event Reliability

Distributed systems require explicit reliability mechanisms.

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

---

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

---

## Transactional Boundaries

The platform avoids distributed transactions across services.

Instead:

```text
Local Transaction
       │
       ▼
Commit
       │
       ▼
Domain Event
       │
       ▼
Other Services
```

This provides eventual consistency while preserving service autonomy.

---

# CI/CD & Deployment

Application and infrastructure delivery follow automated pipelines.

```text
Developer
    │
    ▼
Git Push
    │
    ▼
CI Pipeline
    │
    ├── Unit Tests
    ├── Integration Tests
    ├── Static Analysis
    ├── Security Scan
    ├── Docker Build
    └── Push to ACR
             │
             ▼
        Deployment
             │
             ▼
            AKS
```

A production-grade implementation can use GitOps to separate application
builds from cluster reconciliation.

```text
Git
 │
 ▼
CI
 │
 ▼
Container Registry
 │
 ▼
GitOps Repository
 │
 ▼
Argo CD
 │
 ▼
AKS
```

This provides:

* auditable deployments
* declarative configuration
* automatic reconciliation
* rollback through Git
* environment consistency

---

# Infrastructure as Code

Terraform manages the Azure platform.

```text
infra/terraform/
│
├── bootstrap/
│   ├── main.tf
│   ├── variables.tf
│   └── outputs.tf
│
└── envs/
    └── dev/
        ├── main.tf
        ├── provider.tf
        ├── versions.tf
        ├── backend.tf
        ├── network.tf
        ├── aks.tf
        ├── postgres.tf
        ├── redis.tf
        ├── eventhubs.tf
        ├── keyvault.tf
        ├── acr.tf
        ├── storage.tf
        ├── monitoring.tf
        └── variables.tf
```

---

## Terraform State

Remote state is stored separately from the infrastructure it manages.

```text
Terraform
    │
    ▼
Remote State
    │
    ▼
Azure Storage Account
    │
    ▼
Terraform State
```

This enables:

* state persistence
* team collaboration
* locking
* versioning
* disaster recovery of state

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

Focus:

* service boundaries
* containerization
* infrastructure as code
* managed stateful services
* observability
* workload identity

---

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

Evolution includes:

* private endpoints
* automated TLS
* WAF
* zone redundancy
* HA database configuration
* secret rotation
* stronger network segmentation
* automated backup validation
* disaster recovery testing

---

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

At higher scale, the architecture can evolve toward:

* multi-zone AKS
* multi-region deployment
* geo-replicated databases
* global traffic management
* regional failover
* cross-region event replication
* disaster recovery automation

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
provider without redesigning the business domains.

The AWS equivalent follows the same philosophy and maps the major Azure
services to their AWS counterparts in
[`AWS_ARCHITECTURE.md`](AWS_ARCHITECTURE.md). For an itemized view of what's
running today versus still ahead, see [`PROGRESS.md`](PROGRESS.md).
