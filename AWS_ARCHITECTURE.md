# ☁️ AWS Target Architecture

> 🏗️ **Designed — not deployed.**
>
> The platform's current managed-cloud deployment is **Azure**, implemented under [`infra/terraform/`](infra/terraform/). It currently uses AKS, Azure Database for PostgreSQL, Azure Cache for Redis, Event Hubs, Key Vault, ACR, Azure Storage, and Log Analytics.
>
> **There is currently no AWS Terraform provider or AWS infrastructure implementation in this repository.**
>
> This document describes how the same platform could be deployed on AWS using managed AWS services where there is a strong architectural equivalent, while intentionally preserving application-level decisions that are cloud-independent.
>
> **This is an architecture specification, not documentation of running infrastructure.**
>
> Nothing described on this page is currently provisioned.

---

## Table of Contents

* [Purpose](#purpose)
* [Architecture Principles](#architecture-principles)
* [Current Azure → AWS Mapping](#current-azure--aws-mapping)
* [Target Architecture](#target-architecture)
* [Compute — Amazon EKS](#compute--amazon-eks)
* [Data Layer](#data-layer)
* [Messaging & Event Streaming](#messaging--event-streaming)
* [Secrets & Workload Identity](#secrets--workload-identity)
* [Networking](#networking)
* [Ingress & TLS](#ingress--tls)
* [Object Storage](#object-storage)
* [Observability](#observability)
* [Security Model](#security-model)
* [Availability & Scaling](#availability--scaling)
* [Infrastructure as Code](#infrastructure-as-code)
* [Cost-Constrained POC Posture](#cost-constrained-poc-posture)
* [Deliberately Not Mirrored](#deliberately-not-mirrored)
* [Migration Path](#migration-path)
* [Open Design Decisions](#open-design-decisions)
* [Implementation Status](#implementation-status)

---

## Purpose

The Azure deployment in this repository represents the **currently implemented cloud architecture**.

The AWS design exists for a different purpose:

* demonstrate how the platform maps to another major cloud provider;
* preserve the existing application architecture where practical;
* identify where AWS has a better native equivalent;
* make cloud-specific assumptions explicit;
* provide a future implementation blueprint without pretending it already exists.

The goal is **not** to replace Azure with AWS.

The goal is to demonstrate that the platform's architecture is based on portable engineering decisions rather than being tightly coupled to a single cloud provider.

---

## Architecture Principles

The AWS target follows the same core architectural principles as the Azure implementation.

### 1. Kubernetes as the application runtime

Application workloads remain containerized and run on Kubernetes.

```text
Application
    │
    ▼
Container Image
    │
    ▼
Amazon ECR
    │
    ▼
Amazon EKS
```

The application layer therefore remains largely cloud-agnostic.

---

### 2. Managed services where they provide clear operational value

AWS-managed services are preferred for infrastructure that would otherwise create unnecessary operational overhead:

* EKS instead of self-managed Kubernetes
* RDS instead of self-managed PostgreSQL
* ElastiCache instead of self-managed Redis
* MSK instead of self-managed Kafka
* S3 instead of local object storage
* Secrets Manager instead of Kubernetes Secrets containing long-lived credentials
* CloudWatch for infrastructure and platform telemetry

Application-specific infrastructure such as RabbitMQ and Elasticsearch remains self-hosted because there is currently no architectural requirement to replace them.

---

### 3. Database-per-service isolation

The platform retains the existing **database-per-service** boundary.

For the POC:

```text
RDS PostgreSQL
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

These databases share one PostgreSQL instance for cost efficiency.

This is a **POC cost optimization**, not the intended final production topology.

---

### 4. Least-privilege workload identity

Services should authenticate to AWS using their Kubernetes workload identity rather than static AWS credentials.

```text
Kubernetes ServiceAccount
          │
          ▼
      EKS OIDC
          │
          ▼
       IAM Role
          │
          ├── Secrets Manager
          ├── S3
          └── other explicitly required AWS APIs
```

Each workload receives only the permissions required by that service.

---

### 5. Private-by-default networking

The target architecture keeps application and data infrastructure private.

```text
Internet
   │
   ▼
Public Subnet
   │
   ▼
ALB
   │
   ▼
Private Subnets
   │
   ├── EKS workloads
   ├── RDS
   ├── ElastiCache
   └── MSK
```

The NAT Gateway provides controlled outbound internet access for private workloads that require it.

---

# Current Azure → AWS Mapping

| Concern                    | Azure — Current                               | AWS — Target                                  |
| -------------------------- | --------------------------------------------- | --------------------------------------------- |
| Kubernetes                 | AKS                                           | **Amazon EKS**                                |
| Container Registry         | Azure Container Registry                      | **Amazon ECR**                                |
| PostgreSQL                 | Azure Database for PostgreSQL Flexible Server | **Amazon RDS for PostgreSQL**                 |
| Redis                      | Azure Cache for Redis                         | **Amazon ElastiCache for Redis**              |
| Kafka-compatible streaming | Azure Event Hubs                              | **Amazon MSK**                                |
| Secrets                    | Azure Key Vault                               | **AWS Secrets Manager**                       |
| Object Storage             | Azure Storage Account / Blob Storage          | **Amazon S3**                                 |
| Network                    | Azure VNet                                    | **Amazon VPC**                                |
| Kubernetes identity        | Azure Workload Identity                       | **EKS OIDC + IAM Roles for Service Accounts** |
| Kubernetes authorization   | Azure RBAC                                    | **EKS Access Entries + Kubernetes RBAC**      |
| Container image pulls      | AKS managed identity + AcrPull                | **EKS node IAM role + ECR permissions**       |
| Load Balancing             | NGINX / Azure networking                      | **AWS Load Balancer Controller + ALB**        |
| TLS                        | Azure-managed certificates                    | **AWS Certificate Manager**                   |
| DNS                        | Azure DNS                                     | **Amazon Route 53**                           |
| Logs                       | Log Analytics                                 | **CloudWatch Logs**                           |
| Container metrics          | Azure Container Insights                      | **CloudWatch Container Insights**             |
| Policy enforcement         | Azure Policy                                  | **Kyverno / OPA Gatekeeper**                  |
| RabbitMQ                   | Self-hosted in Kubernetes                     | **Self-hosted in Kubernetes**                 |
| Elasticsearch              | Self-hosted in Kubernetes                     | **Self-hosted in Kubernetes**                 |

The mapping is intentionally **architectural rather than product-name driven**. Where there is no strong one-to-one equivalent, the AWS design preserves the application requirement instead of forcing a managed AWS product into the architecture.

---

# Target Architecture

```text
                                      ┌─────────────────────┐
                                      │      Internet       │
                                      └──────────┬──────────┘
                                                 │
                                                 ▼
                                      ┌─────────────────────┐
                                      │     Route 53        │
                                      │        DNS          │
                                      └──────────┬──────────┘
                                                 │
                                                 ▼
                                      ┌─────────────────────┐
                                      │         ACM         │
                                      │     TLS Certificate │
                                      └──────────┬──────────┘
                                                 │
                                                 ▼
                              ┌─────────────────────────────────┐
                              │ Application Load Balancer (ALB) │
                              │    AWS Load Balancer Controller │
                              └────────────────┬────────────────┘
                                               │
═══════════════════════════════════════════════▼════════════════════════════════════════
                                  Amazon VPC
                              ┌─────────────────────┐
                              │    Public Subnets   │
                              │                     │
                              │       ALB           │
                              └──────────┬──────────┘
                                         │
                                         ▼
                              ┌─────────────────────┐
                              │   Private Subnets   │
                              │                     │
                              │     Amazon EKS      │
                              │                     │
                              │ ┌─────────────────┐ │
                              │ │ System Nodes    │ │
                              │ │                 │ │
                              │ │ Core add-ons    │ │
                              │ └─────────────────┘ │
                              │                     │
                              │ ┌─────────────────┐ │
                              │ │ Application      │ │
                              │ │ Nodes            │ │
                              │ │                 │ │
                              │ │ Identity        │ │
                              │ │ Patient         │ │
                              │ │ Doctor          │ │
                              │ │ Appointment     │ │
                              │ │ EMR             │ │
                              │ │ Billing         │ │
                              │ │ Notification    │ │
                              │ │ Audit           │ │
                              │ │ Analytics       │ │
                              │ │ GraphQL Gateway │ │
                              │ │                 │ │
                              │ │ RabbitMQ        │ │
                              │ │ Elasticsearch   │ │
                              │ └─────────────────┘ │
                              └───────┬───┬───┬─────┘
                                      │   │   │
                    ┌─────────────────┘   │   └──────────────────┐
                    │                     │                      │
                    ▼                     ▼                      ▼
          ┌────────────────┐   ┌──────────────────┐   ┌──────────────────┐
          │ Amazon RDS     │   │ ElastiCache      │   │ Amazon MSK       │
          │ PostgreSQL     │   │ Redis            │   │ Kafka            │
          │                │   │                  │   │                  │
          │ 9 databases    │   │ Cache layer      │   │ Domain events    │
          │ + 9 roles      │   │                  │   │                  │
          └────────────────┘   └──────────────────┘   └──────────────────┘


             ┌────────────────┐      ┌────────────────┐
             │ AWS Secrets    │      │ Amazon S3      │
             │ Manager        │      │                │
             │                │      │ EMR attachments│
             │ DB credentials │      │                │
             │ JWT secret     │      │ Versioning     │
             │ Redis secret   │      │ Encryption     │
             └────────────────┘      └────────────────┘


                         ┌────────────────────────────┐
                         │      CloudWatch            │
                         │                            │
                         │ Logs · Metrics · Alarms    │
                         │ Container Insights         │
                         └────────────────────────────┘
```

---

# Compute — Amazon EKS

Amazon EKS is the target Kubernetes runtime.

The design intentionally mirrors the current AKS topology rather than introducing unnecessary architectural changes.

## Node Groups

### System node group

Always present.

Responsible for:

* Kubernetes system workloads;
* CoreDNS;
* kube-proxy / networking components;
* AWS Load Balancer Controller;
* metrics and observability agents;
* other cluster-level add-ons.

### Application node group

Optional in the cost-constrained POC.

Responsible for:

* Spring Boot microservices;
* GraphQL Gateway;
* RabbitMQ;
* Elasticsearch;
* other application workloads.

The application node group can be enabled once capacity requirements justify separating system and application workloads.

---

## Autoscaling

The target supports either:

* **Cluster Autoscaler**, if retaining managed node groups; or
* **Karpenter**, if more dynamic node provisioning is desired.

For a first AWS implementation, Cluster Autoscaler provides the closest conceptual match to the existing AKS configuration.

A future production deployment could migrate to Karpenter for more efficient workload-driven capacity provisioning.

---

## Networking

The EKS cluster should use private subnets.

Recommended topology:

```text
VPC
│
├── Public Subnets
│   └── Application Load Balancer
│
└── Private Subnets
    ├── EKS nodes
    ├── RDS
    ├── ElastiCache
    └── MSK
```

The exact subnet layout should span at least two Availability Zones for production deployments.

---

## Cluster Access

The AWS target should use:

**EKS Access Entries + IAM**

instead of distributing static administrator kubeconfigs.

Conceptually:

```text
Developer / CI
      │
      ▼
    AWS IAM
      │
      ▼
EKS Access Entry
      │
      ▼
Kubernetes RBAC
```

This provides centralized AWS identity management while retaining Kubernetes-level authorization.

---

# Data Layer

## Amazon RDS for PostgreSQL

The POC retains the current database topology:

```text
                 RDS PostgreSQL
                       │
        ┌──────────────┼──────────────┐
        │              │              │
        ▼              ▼              ▼
   identity_db    patient_db     doctor_db
        │              │              │
        └──────────────┼──────────────┘
                       │
                remaining services
```

Each service owns:

* its own database;
* its own PostgreSQL role;
* its own credentials.

Cross-service database access is intentionally avoided.

### POC topology

One RDS instance:

```text
1 PostgreSQL Instance
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

### Production evolution

A production deployment could move toward:

```text
Identity       → RDS
Patient        → RDS
Doctor         → RDS
Appointment    → RDS
EMR            → RDS
Billing        → RDS
Notification   → RDS
Audit          → RDS
Analytics      → RDS
```

with separate instances introduced where isolation, scaling, compliance, or availability requirements justify the additional cost.

---

## RDS Networking

The preferred production design is **private RDS connectivity**.

EKS workloads communicate with RDS over private VPC networking:

```text
EKS Pod
  │
  ▼
Private VPC
  │
  ▼
RDS PostgreSQL
```

Security groups should permit PostgreSQL traffic only from the relevant EKS workload/node security boundary.

The current Azure POC uses public PostgreSQL access because Terraform directly connects to PostgreSQL during provisioning to create service databases and roles.

The AWS target should treat this as a **provisioning concern**, not a runtime requirement.

A future implementation can solve this using:

* a bastion host;
* AWS Systems Manager;
* a temporary provisioning job inside the VPC;
* or Terraform executed from a network location with private access.

This allows runtime databases to remain private without coupling the application architecture to public database endpoints.

---

# Cache — Amazon ElastiCache

Amazon ElastiCache for Redis replaces Azure Cache for Redis.

Current application usage remains intentionally limited:

```text
Patient Service ──────┐
                      ├──► Redis
Doctor Service ───────┘
```

Redis is currently used primarily for application caching rather than as the system of record.

The architecture should therefore tolerate cache loss and rebuild cached data from PostgreSQL.

Production configuration should consider:

* encryption in transit;
* encryption at rest;
* authentication;
* subnet groups;
* security groups;
* Multi-AZ / replication where required.

---

# Messaging & Event Streaming

## Amazon MSK

Amazon MSK is the direct Kafka-oriented target for Azure Event Hubs.

This is important because the application already uses Kafka APIs.

```text
Spring Boot Services
        │
        ▼
Kafka Producer / Consumer
        │
        ▼
Amazon MSK
```

No application-level messaging abstraction needs to change merely because the cloud provider changes.

---

## Topics

The initial topology mirrors the current platform:

```text
appointment.events
billing.events
notification.requests
```

Initial POC configuration:

* 2 partitions;
* 3-day retention.

These values are capacity decisions rather than application contracts and should increase based on observed traffic.

---

## Kafka Authentication

The AWS implementation should prefer **MSK IAM authentication** where supported by the selected MSK configuration.

This avoids distributing long-lived Kafka usernames/passwords when AWS IAM can provide workload-level authentication.

If SASL/SCRAM is selected instead, credentials should be stored in Secrets Manager and accessed through the workload identity mechanism.

---

# RabbitMQ

RabbitMQ remains self-hosted inside EKS.

```text
Notification Service
        │
        ▼
   RabbitMQ
        │
        ├── Retry
        └── Dead Letter Queue
```

Amazon MQ is deliberately not introduced in the first AWS design because RabbitMQ is currently an internal infrastructure component and there is no requirement to migrate its operational model.

A future production architecture can evaluate:

* Amazon MQ for RabbitMQ;
* a dedicated RabbitMQ cluster;
* or an alternative managed messaging strategy.

---

# Secrets & Workload Identity

## AWS Secrets Manager

Secrets Manager replaces Azure Key Vault.

Expected secret inventory includes:

```text
JWT signing secret
Database credentials
Redis credentials
Kafka credentials (if required)
Third-party integration secrets
Application secrets
```

Secrets should not be committed to Git or embedded into container images.

---

## IAM Roles for Service Accounts

Each Kubernetes workload receives its own IAM role.

Example:

```text
patient-service
      │
      ▼
patient-service ServiceAccount
      │
      ▼
IAM Role
      │
      ├── Read patient-service secrets
      └── Read/write patient-specific S3 prefix
```

Another service receives a different role:

```text
emr-service
      │
      ▼
emr-service ServiceAccount
      │
      ▼
IAM Role
      │
      ├── Read EMR secrets
      └── Read/write s3://bucket/emr/
```

This prevents a compromised workload from automatically receiving access to every AWS resource.

> **Important:** IRSA is for workload access to AWS APIs such as Secrets Manager and S3. ECR image pulls are normally handled by the EKS node IAM role or another supported EKS image-pull mechanism; application pods should not require ECR credentials.

---

# Networking

## VPC

The target architecture uses a dedicated VPC with multiple Availability Zones.

```text
                    VPC
                     │
        ┌────────────┴────────────┐
        │                         │
   Availability Zone A       Availability Zone B
        │                         │
   ┌────┴────┐               ┌────┴────┐
   │ Public  │               │ Public  │
   │ Subnets │               │ Subnets │
   └─────────┘               └─────────┘
        │                         │
   ┌────┴────┐               ┌────┴────┐
   │ Private │               │ Private │
   │ Subnets │               │ Subnets │
   └─────────┘               └─────────┘
```

### Public subnets

Used primarily for:

* Application Load Balancer;
* NAT Gateway infrastructure.

### Private subnets

Used for:

* EKS worker nodes;
* RDS;
* ElastiCache;
* MSK;
* internal workloads.

---

## Security Groups

Security groups should implement service-level network boundaries.

Conceptually:

```text
Internet
   │
   ▼
ALB :443
   │
   ▼
EKS workloads :8080
   │
   ├──► RDS :5432
   ├──► Redis :6379
   └──► MSK :9098/9094
```

No public access should be granted to:

* PostgreSQL;
* Redis;
* Kafka;
* RabbitMQ;
* Elasticsearch;
* Kubernetes worker nodes.

---

## NAT Gateway

Private workloads may require outbound access for:

* package repositories;
* external APIs;
* AWS services without a VPC endpoint;
* container/application dependencies.

The NAT Gateway provides controlled outbound internet connectivity.

For a production architecture, AWS VPC endpoints should be evaluated for services such as:

* S3;
* ECR;
* CloudWatch;
* Secrets Manager.

This can reduce unnecessary NAT traffic and improve security.

---

# Ingress & TLS

The preferred AWS-native edge architecture is:

```text
Internet
   │
   ▼
Route 53
   │
   ▼
ACM Certificate
   │
   ▼
Application Load Balancer
   │
   ▼
AWS Load Balancer Controller
   │
   ▼
Kubernetes Services
```

TLS terminates at the ALB using AWS Certificate Manager.

The existing Kubernetes ingress configuration can either:

1. be adapted to AWS Load Balancer Controller annotations; or
2. retain NGINX behind an AWS load balancer if application-level ingress portability is prioritized.

The first option is preferable for an AWS-native deployment because it integrates naturally with:

* ACM;
* WAF;
* ALB;
* Route 53;
* AWS security controls.

---

# Object Storage

Amazon S3 replaces Azure Blob Storage.

The primary bucket is intended for EMR attachments:

```text
s3://<environment>-emr-attachments/
```

Recommended logical layout:

```text
emr/
├── patients/
│   └── <patient-id>/
│       ├── documents/
│       └── attachments/
└── temporary/
```

The application should never rely on public bucket access.

Recommended controls include:

* Block Public Access;
* server-side encryption;
* bucket versioning;
* lifecycle policies;
* IAM-based access;
* optional Object Lock for audit/compliance requirements.

---

# Observability

The existing application observability stack remains cloud-independent.

```text
                   ┌───────────────┐
                   │ Spring Boot   │
                   │ Microservices │
                   └───────┬───────┘
                           │
            ┌──────────────┼───────────────┐
            │              │               │
            ▼              ▼               ▼
       Prometheus       Zipkin         Application Logs
            │                              │
            ▼                              ▼
         Grafana                      CloudWatch
```

## AWS-native telemetry

CloudWatch provides:

* EKS control-plane logs;
* container logs;
* infrastructure metrics;
* alarms;
* operational dashboards;
* Container Insights.

## Application observability

The application continues using:

* Prometheus;
* Grafana;
* Zipkin.

This prevents the application monitoring model from becoming tightly coupled to Azure or AWS.

---

# Security Model

The AWS architecture follows a defense-in-depth model.

## Identity

```text
Human
 │
 └──► IAM / SSO
          │
          ▼
     EKS Access Entries
```

## Workloads

```text
Pod
 │
 └──► Kubernetes ServiceAccount
          │
          ▼
       IAM Role
          │
          └──► Explicit AWS permissions
```

## Network

```text
Internet
   │
   ▼
ALB
   │
   ▼
Private EKS
   │
   ├── RDS
   ├── Redis
   └── MSK
```

## Data

Sensitive data should be protected using:

* encryption at rest;
* encryption in transit;
* IAM;
* security groups;
* least-privilege access;
* secret rotation where supported;
* private networking;
* audit logging.

For real healthcare/PHI workloads, the final AWS implementation would additionally require a formal compliance review and AWS service eligibility assessment rather than assuming that this POC architecture is automatically production-compliant.

---

# Availability & Scaling

The POC intentionally favors cost efficiency.

Production should evolve toward multi-AZ infrastructure.

## Target production topology

```text
                 Internet
                    │
                    ▼
                  ALB
               ┌────┴────┐
               ▼         ▼
            AZ-A       AZ-B
               │         │
             EKS       EKS
               │         │
               └────┬────┘
                    │
              Managed Services
                    │
          ┌─────────┼─────────┐
          ▼         ▼         ▼
         RDS      Redis      MSK
```

Potential scaling dimensions include:

* EKS horizontal pod autoscaling;
* cluster/node autoscaling;
* RDS read replicas;
* ElastiCache replication;
* MSK partition scaling;
* S3 virtually unlimited object storage;
* independent service scaling.

---

# Infrastructure as Code

The AWS implementation should follow the existing Terraform structure rather than creating a separate ad-hoc infrastructure model.

A future implementation could evolve toward:

```text
infra/
└── terraform/
    ├── bootstrap/
    │   ├── backend
    │   └── state
    │
    ├── modules/
    │   ├── vpc/
    │   ├── eks/
    │   ├── rds/
    │   ├── redis/
    │   ├── msk/
    │   ├── ecr/
    │   ├── s3/
    │   ├── secrets/
    │   └── observability/
    │
    └── envs/
        ├── dev/
        ├── staging/
        └── prod/
```

The implementation should preserve the repository's existing principles:

* reusable modules;
* environment-specific configuration;
* remote state;
* explicit variables;
* outputs between modules;
* least-privilege IAM;
* predictable naming;
* reproducible deployments.

---

# Terraform State

The Azure implementation uses an Azure Storage Account for Terraform remote state.

The AWS equivalent would be:

```text
Terraform
    │
    ▼
Amazon S3
    │
    └── Terraform state

Optional / implementation-dependent
    │
    └── state locking mechanism
```

The backend should be bootstrapped before the main infrastructure is deployed.

State should never be committed to Git.

---

# Cost-Constrained POC Posture

The current Azure infrastructure was explicitly constrained by development/free-tier capacity.

The AWS design preserves that philosophy where it makes architectural sense.

### POC

```text
EKS
└── Minimal node capacity

RDS
└── One PostgreSQL instance
    └── Multiple service databases

Redis
└── Minimal ElastiCache configuration

MSK
└── Minimal cluster configuration

S3
└── Single application bucket
```

### Production

The architecture can evolve independently:

```text
EKS
├── Multiple AZs
├── Dedicated application capacity
└── Autoscaling

RDS
├── Multi-AZ
├── Read replicas
└── Service-specific isolation where required

Redis
└── Replication / Multi-AZ

MSK
├── Multiple brokers
└── Increased partitions

S3
├── Versioning
├── Lifecycle policies
└── Replication if required
```

The POC topology is therefore a **cost optimization**, not a statement that the production platform should use the same capacity or availability configuration.

---

# Deliberately Not Mirrored

## Amazon OpenSearch

The current platform contains Elasticsearch, but no production consumer currently depends on it.

Therefore, this design does not automatically replace it with Amazon OpenSearch.

The migration should happen only when Elasticsearch becomes an actual application dependency.

---

## Amazon MQ

RabbitMQ remains self-hosted because the current architecture does not justify changing the messaging infrastructure model.

Amazon MQ can be evaluated later if managed RabbitMQ becomes operationally valuable.

---

## Multi-region deployment

Multi-region architecture is intentionally outside the current scope.

A future design could introduce:

```text
Region A
   │
   ├── EKS
   ├── RDS
   ├── MSK
   └── S3
        │
        ▼
Region B
   │
   ├── EKS
   ├── RDS
   ├── MSK
   └── S3
```

However, this introduces significant complexity around:

* database replication;
* Kafka replication;
* traffic routing;
* distributed consistency;
* disaster recovery;
* secrets;
* operational ownership.

It should therefore be driven by an explicit availability requirement rather than added for architectural appearance.

---

# Migration Path

The application architecture is intentionally designed so that an AWS deployment does not require rewriting the business services.

The conceptual migration is:

```text
                 Existing Application
                        │
              ┌─────────┴─────────┐
              │                   │
              ▼                   ▼
           Azure               AWS Target
              │                   │
              ▼                   ▼
             AKS                 EKS
              │                   │
              ▼                   ▼
          PostgreSQL            RDS
              │                   │
              ▼                   ▼
           Redis             ElastiCache
              │                   │
              ▼                   ▼
        Event Hubs                MSK
              │                   │
              ▼                   ▼
         Key Vault          Secrets Manager
              │                   │
              ▼                   ▼
          Blob Storage             S3
```

Application-level contracts remain stable:

* PostgreSQL;
* Redis;
* Kafka;
* HTTP/gRPC;
* GraphQL;
* S3-compatible object-storage concepts;
* Kubernetes;
* container images.

The primary changes are therefore concentrated in the infrastructure layer.

---

# Open Design Decisions

The following decisions must be resolved before implementing real AWS Terraform.

### 1. EKS networking

Choose between:

* AWS VPC CNI;
* Cilium;
* another supported networking model.

The current Azure implementation uses Cilium, but AWS networking has different operational and performance trade-offs.

---

### 2. Autoscaling

Choose between:

* Cluster Autoscaler;
* Karpenter.

Cluster Autoscaler is the simpler migration path.

Karpenter may be preferable for a production environment with heterogeneous workloads and dynamic capacity requirements.

---

### 3. Ingress

Choose between:

* AWS Load Balancer Controller + ALB;
* NGINX Ingress behind an AWS load balancer.

The AWS-native ALB approach is the preferred target.

---

### 4. RDS topology

Determine whether production requires:

* single-AZ;
* Multi-AZ;
* read replicas;
* separate RDS instances per service.

The POC should retain the single-instance database-per-service model for cost efficiency.

---

### 5. Kafka authentication

Prefer:

* MSK IAM authentication.

Alternative:

* SASL/SCRAM credentials stored in Secrets Manager.

---

### 6. Policy enforcement

Azure Policy does not have a direct one-click AWS EKS equivalent.

Evaluate:

* Kyverno;
* OPA Gatekeeper;
* another Kubernetes admission-policy solution.

---

### 7. Terraform state

Finalize:

* S3 backend;
* state locking strategy;
* encryption;
* IAM access;
* state recovery/versioning.

---

### 8. Production compliance

Before processing real healthcare data, evaluate:

* AWS service eligibility for the required compliance regime;
* encryption requirements;
* audit requirements;
* retention;
* access logging;
* backup/restore;
* disaster recovery;
* data residency;
* incident response.

The architecture shown here is **not a compliance certification**.

---

# Implementation Status

| Component                    |         Azure |               AWS |
| ---------------------------- | ------------: | ----------------: |
| Kubernetes                   | ✅ Implemented |       📋 Designed |
| PostgreSQL                   | ✅ Implemented |       📋 Designed |
| Redis                        | ✅ Implemented |       📋 Designed |
| Kafka/Event Streaming        | ✅ Implemented |       📋 Designed |
| Secrets                      | ✅ Implemented |       📋 Designed |
| Object Storage               | ✅ Implemented |       📋 Designed |
| Container Registry           | ✅ Implemented |       📋 Designed |
| Networking                   | ✅ Implemented |       📋 Designed |
| Kubernetes Workload Identity | ✅ Implemented |       📋 Designed |
| Ingress                      | ✅ Implemented |       📋 Designed |
| Observability                | ✅ Implemented |       📋 Designed |
| Terraform                    | ✅ Implemented | ❌ Not implemented |
| Multi-region DR              |    📋 Planned |        📋 Planned |

---

## Final Architecture Position

The AWS target intentionally demonstrates **cloud portability without pretending that Azure and AWS are identical**.

The application architecture remains centered around:

```text
                    ┌──────────────────────┐
                    │      Frontend        │
                    └──────────┬───────────┘
                               │
                               ▼
                    ┌──────────────────────┐
                    │   API / GraphQL      │
                    │       Gateway        │
                    └──────────┬───────────┘
                               │
              ┌────────────────┼────────────────┐
              ▼                ▼                ▼
        Microservices      Microservices     Microservices
              │                │                │
              └────────────────┼────────────────┘
                               │
          ┌────────────────────┼────────────────────┐
          ▼                    ▼                    ▼
      PostgreSQL             Redis                Kafka
          │                                         │
          └────────────────────┬────────────────────┘
                               ▼
                       Event-driven workflows
```

AWS becomes an **infrastructure implementation of the same platform**, not a reason to redesign the business architecture.

> **Status:** 🏗️ Architecture designed
> **Deployment:** ❌ Not implemented
> **Terraform:** ❌ No AWS provider/configuration currently exists
> **Purpose:** Cloud architecture specification / future implementation blueprint
