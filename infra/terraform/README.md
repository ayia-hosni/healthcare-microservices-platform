# Terraform

Infrastructure as Code for deploying the healthcare platform to **Azure** and **AWS**.

Terraform provisions the cloud infrastructure up to:

> **Managed Kubernetes cluster + managed data services + cloud secrets → ready for Helm/Kubernetes deployment**

Terraform does **not** deploy the application workloads themselves.

The 9 Spring Boot microservices, GraphQL gateway, Angular frontend, and in-cluster infrastructure are deployed separately using:

* [`infra/helm`](../helm) — Helm charts
* [`infra/k8s`](../k8s) — Kubernetes manifests / Kustomize

---

# Table of Contents

* [Architecture](#architecture)
* [Cloud Targets](#cloud-targets)
* [What Terraform Owns](#what-terraform-owns)
* [What Kubernetes Owns](#what-kubernetes-owns)
* [Directory Structure](#directory-structure)
* [Layered Architecture](#layered-architecture)
* [Why Four Layers](#why-four-layers)
* [Azure vs AWS](#azure-vs-aws)
* [Prerequisites](#prerequisites)
* [Choose a Cloud](#choose-a-cloud)
* [Deployment Flow](#deployment-flow)
* [Deploy Azure](#deploy-azure)
* [Deploy AWS](#deploy-aws)
* [Connecting Terraform to Helm](#connecting-terraform-to-helm)
* [Environment Strategy](#environment-strategy)
* [State Management](#state-management)
* [CI/CD](#cicd)
* [Teardown](#teardown)
* [Important Design Decisions](#important-design-decisions)
* [Security Notes](#security-notes)
* [Quick Reference](#quick-reference)

---

# Architecture

The repository supports two independent cloud implementations:

```text
                           Terraform
                              │
                 ┌────────────┴────────────┐
                 │                         │
                 ▼                         ▼
              Azure                       AWS
                 │                         │
                 ▼                         ▼
                AKS                       EKS
                 │                         │
        ┌────────┼────────┐       ┌────────┼────────┐
        │        │        │       │        │        │
        ▼        ▼        ▼       ▼        ▼        ▼
     Postgres  Redis   Event    RDS     Redis     MSK
                       Hubs
        │        │        │       │        │        │
        └────────┼────────┘       └────────┼────────┘
                 │                         │
                 ▼                         ▼
          Blob Storage                  S3
          Key Vault                 Secrets Manager
```

The application layer is intentionally independent of the cloud infrastructure layer:

```text
Terraform
   │
   ▼
Cloud infrastructure
   │
   ▼
AKS / EKS
   │
   ▼
Helm / Kubernetes
   │
   ├── Identity Service
   ├── Patient Service
   ├── Doctor Service
   ├── Appointment Service
   ├── EMR Service
   ├── Billing Service
   ├── Notification Service
   ├── Audit Service
   ├── Analytics Service
   ├── GraphQL Gateway
   └── Angular Frontend
```

This separation means infrastructure and application deployment can evolve independently.

---

# Cloud Targets

| Tree              | Cloud                                                                        | Status                           | Documentation                        |
| ----------------- | ---------------------------------------------------------------------------- | -------------------------------- | ------------------------------------ |
| [`azure/`](azure) | Azure — AKS, PostgreSQL Flexible Server, Event Hubs, Key Vault, Blob Storage | **Provisioned**                  | [`azure/README.md`](azure/README.md) |
| [`aws/`](aws)     | AWS — EKS, RDS, ElastiCache, MSK/self-hosted Kafka, Secrets Manager, S3      | **Designed / runbook available** | [`aws/README.md`](aws/README.md)     |

Cloud architecture documentation:

```text
docs/cloud/
├── azure/
└── aws/
```

The Azure and AWS trees are **completely independent**.

You can:

* deploy Azure only
* deploy AWS only
* deploy both
* destroy one without affecting the other

They do not share Terraform state.

---

# What Terraform Owns

Terraform owns the **cloud infrastructure lifecycle**.

Depending on the cloud, this includes:

### Networking

```text
VPC / VNet
Subnets
Internet Gateway / NAT
Security Groups / NSGs
Network endpoints
```

### Kubernetes

```text
EKS / AKS
Node groups / node pools
OIDC / Workload Identity
Cluster authentication
Load-balancer integration
Storage CSI integration
```

### Data

```text
PostgreSQL
Redis
Kafka-compatible messaging
Object storage
```

### Security

```text
Secrets Manager / Key Vault
IAM / Azure RBAC
Workload identity
GitHub Actions OIDC
```

### Container infrastructure

```text
ECR / ACR
```

### Observability

```text
CloudWatch / Log Analytics
Container Insights
```

---

# What Kubernetes Owns

Terraform intentionally stops before application deployment.

Kubernetes/Helm owns:

```text
Application workloads
├── Identity Service
├── Patient Service
├── Doctor Service
├── Appointment Service
├── EMR Service
├── Billing Service
├── Notification Service
├── Audit Service
├── Analytics Service
├── GraphQL Gateway
└── Angular Frontend
```

It also owns infrastructure that remains self-hosted:

```text
RabbitMQ
Elasticsearch
```

Kafka is also self-hosted in Kubernetes on AWS when:

```text
kafka_mode = "self-hosted"
```

The deployment boundary is therefore:

```text
Terraform
─────────
Cloud infrastructure
Managed services
Cluster
Cloud identities
Cloud secrets
        │
        ▼
Helm / Kubernetes
─────────────────
Application workloads
RabbitMQ
Elasticsearch
Self-hosted Kafka (AWS option)
```

---

# Directory Structure

Both cloud trees follow the same overall structure:

```text
terraform/
│
├── README.md
│
├── azure/
│   ├── README.md
│   ├── bootstrap/
│   ├── modules/
│   └── envs/
│       └── dev/
│           ├── 10-network/
│           ├── 20-platform/
│           ├── 30-data/
│           └── 40-cicd/
│
└── aws/
    ├── README.md
    ├── bootstrap/
    ├── modules/
    └── envs/
        └── dev/
            ├── 10-network/
            ├── 20-platform/
            ├── 30-data/
            └── 40-cicd/
```

Each cloud contains:

### `bootstrap/`

Creates the remote Terraform state backend.

```text
Azure → Storage Account
AWS   → S3 bucket + DynamoDB lock
```

Bootstrap state itself remains local.

### `modules/`

Reusable cloud-specific infrastructure components.

For example:

```text
modules/
├── network/
├── aks/ or eks/
├── data/
├── storage/
├── registry/
├── keyvault/ or secrets/
├── monitoring/
└── cicd/
```

### `envs/`

Environment-specific Terraform root modules.

Current environment:

```text
envs/dev/
```

Future environments can reuse the same modules:

```text
envs/
├── dev/
├── staging/
└── production/
```

---

# Layered Architecture

Each environment is split into four independently managed layers.

| Layer         | Responsibility                                        | Depends on                  |
| ------------- | ----------------------------------------------------- | --------------------------- |
| `10-network`  | VPC/VNet, subnets, NAT, security boundaries           | —                           |
| `20-platform` | EKS/AKS, registry, identity, cluster integrations     | `10-network`                |
| `30-data`     | PostgreSQL, Redis, Kafka/Event Hubs, storage, secrets | `10-network`, `20-platform` |
| `40-cicd`     | GitHub Actions OIDC + deployment permissions          | `20-platform`               |

The normal deployment order is:

```text
10-network
     │
     ▼
20-platform
     │
     ▼
30-data

40-cicd
     │
     └── can be applied after 20-platform
```

Therefore:

```text
10 → 20 → 30

40 → anytime after 20
```

---

# Why Four Layers?

The layers intentionally have **separate Terraform state**.

For example:

```text
10-network
   └── network.tfstate

20-platform
   └── platform.tfstate

30-data
   └── data.tfstate

40-cicd
   └── cicd.tfstate
```

This provides blast-radius isolation.

A change to the data layer cannot accidentally manage the VPC or destroy the Kubernetes cluster.

For example:

```text
terraform apply
     │
     ▼
30-data
     │
     ├── PostgreSQL
     ├── Redis
     ├── Kafka/Event Hubs
     └── Storage
```

The `30-data` root does not manage:

```text
VPC / VNet
AKS / EKS
Node groups
```

---

# Why Modules?

Modules contain reusable infrastructure building blocks.

For example:

```text
envs/dev/20-platform
        │
        ├── network outputs
        │
        ├── module "aks"
        ├── module "registry"
        ├── module "monitoring"
        └── module "keyvault"
```

When creating another environment:

```text
envs/staging/
```

the same modules can be reused rather than copied.

This avoids:

```text
dev infrastructure
        +
staging copied infrastructure
        +
production copied infrastructure
        =
configuration drift
```

Instead:

```text
                modules/
               /       \
            dev       staging
             │           │
             └─────┬─────┘
                   │
            same infrastructure
              building blocks
```

---

# Azure vs AWS

The two implementations aim for functional parity while using native cloud services.

| Concern                | Azure                              | AWS                             |
| ---------------------- | ---------------------------------- | ------------------------------- |
| Kubernetes             | AKS                                | EKS                             |
| Container registry     | ACR                                | ECR                             |
| PostgreSQL             | Flexible Server                    | RDS PostgreSQL                  |
| Cache                  | Azure Cache for Redis              | ElastiCache for Redis           |
| Kafka                  | Event Hubs Kafka endpoint          | MSK or self-hosted Kafka        |
| Object storage         | Blob Storage                       | S3                              |
| Secrets                | Key Vault                          | Secrets Manager                 |
| Pod → cloud auth       | Workload Identity                  | IRSA                            |
| CI authentication      | Entra app + GitHub OIDC            | IAM role + GitHub OIDC          |
| Observability          | Log Analytics + Container Insights | CloudWatch + Container Insights |
| RabbitMQ               | In-cluster                         | In-cluster                      |
| Elasticsearch          | In-cluster                         | In-cluster                      |
| Kafka when self-hosted | —                                  | In-cluster                      |

The application therefore has a common Kubernetes architecture with cloud-specific infrastructure adapters.

---

# Prerequisites

Install:

```text
Terraform
kubectl
Helm
Git
```

For Azure:

```text
Azure CLI
```

For AWS:

```text
AWS CLI
```

Verify:

```bash
terraform version
kubectl version --client
helm version
```

Then authenticate to the cloud you want to deploy.

### Azure

```bash
az login
az account show
```

### AWS

```bash
aws configure
aws sts get-caller-identity
```

---

# Choose a Cloud

You do **not** need to deploy both.

## Azure

Use Azure when the target environment is:

```text
Azure
└── AKS
    ├── PostgreSQL Flexible Server
    ├── Azure Cache for Redis
    ├── Event Hubs
    ├── Blob Storage
    └── Key Vault
```

Start here:

[`azure/README.md`](azure/README.md)

---

## AWS

Use AWS when the target environment is:

```text
AWS
└── EKS
    ├── RDS PostgreSQL
    ├── ElastiCache
    ├── MSK / self-hosted Kafka
    ├── S3
    └── Secrets Manager
```

Start here:

[`aws/README.md`](aws/README.md)

---

# Deployment Flow

Regardless of the cloud, the deployment follows the same conceptual flow:

```text
1. Authenticate to cloud
          │
          ▼
2. Bootstrap Terraform state
          │
          ▼
3. Create network
          │
          ▼
4. Create Kubernetes platform
          │
          ▼
5. Create managed data services
          │
          ▼
6. Create CI/CD identity
          │
          ▼
7. Configure kubectl
          │
          ▼
8. Deploy Helm/Kubernetes workloads
```

Terraform itself handles steps:

```text
1 → 6
```

Kubernetes/Helm handles:

```text
7 → 8
```

---

# Deploy Azure

From:

```text
infra/terraform/
```

run:

```bash
cd azure/bootstrap

terraform init
terraform apply
```

Use the generated backend configuration for the environment layers.

Then:

```bash
cd ../envs/dev
```

Apply:

```bash
10-network
20-platform
30-data
```

Then:

```text
40-cicd
```

See the complete Azure guide:

[`azure/README.md`](azure/README.md)

---

# Deploy AWS

From:

```text
infra/terraform/
```

run:

```bash
cd aws/bootstrap

terraform init
terraform apply
```

Then configure the environment layers:

```bash
cd ../envs/dev
```

Apply:

```text
10-network
20-platform
30-data
```

Then:

```text
40-cicd
```

See the complete AWS guide:

[`aws/README.md`](aws/README.md)

---

# Connecting Terraform to Helm

Terraform exposes infrastructure outputs that the Kubernetes deployment consumes.

The general flow is:

```text
Terraform outputs
       │
       ▼
Helm values / Kubernetes configuration
       │
       ▼
Application pods
```

Typical platform outputs include:

```text
Cluster name
Container registry
Workload identity
Load balancer identity
```

Typical data outputs include:

```text
PostgreSQL endpoint
Redis endpoint
Kafka/Event Hubs endpoint
Object storage
Secret paths
```

The exact outputs differ between Azure and AWS.

See the cloud-specific README for the complete output list.

---

# Environment Strategy

The current environment is:

```text
envs/dev
```

Additional environments should reuse the existing modules:

```text
envs/
├── dev/
│   ├── 10-network/
│   ├── 20-platform/
│   ├── 30-data/
│   └── 40-cicd/
│
├── staging/
│   ├── 10-network/
│   ├── 20-platform/
│   ├── 30-data/
│   └── 40-cicd/
│
└── production/
    ├── 10-network/
    ├── 20-platform/
    ├── 30-data/
    └── 40-cicd/
```

Each environment must have:

* its own Terraform state
* its own backend keys
* its own `terraform.tfvars`
* its own cloud resources

For example:

```text
dev/network.tfstate
staging/network.tfstate
production/network.tfstate
```

Do **not** reuse development state for staging or production.

---

# State Management

Terraform state is intentionally separated by cloud, environment, and layer.

Conceptually:

```text
Azure
└── state backend
    ├── dev/10-network
    ├── dev/20-platform
    ├── dev/30-data
    └── dev/40-cicd

AWS
└── state backend
    ├── dev/10-network
    ├── dev/20-platform
    ├── dev/30-data
    └── dev/40-cicd
```

The two clouds do not share state.

This is important because:

```text
AWS failure
   ≠
Azure failure
```

and:

```text
Azure destroy
   ≠
AWS destroy
```

---

## Sensitive State

Terraform state may contain sensitive information such as:

* generated passwords
* database credentials
* secret values
* resource identifiers
* connection information

Therefore:

> **Treat Terraform state as sensitive infrastructure data.**

Never commit:

```text
*.tfstate
*.tfstate.*
.terraform/
terraform.tfvars
```

unless explicitly safe and intended.

---

# CI/CD

The `40-cicd` layer creates the cloud identity used by GitHub Actions.

The architecture is:

```text
GitHub Actions
      │
      │ OIDC
      ▼
Cloud identity
      │
      ├── Container Registry
      │
      └── Kubernetes
```

There are no long-lived cloud credentials stored in GitHub Actions.

### Azure

```text
GitHub
   │
   ▼
Entra Application
   │
   ├── AcrPush
   └── AKS RBAC
```

### AWS

```text
GitHub
   │
   ▼
IAM Role
   │
   ├── ECR Push
   └── EKS access
```

The CI identity can therefore:

1. Authenticate to the cloud.
2. Build Docker images.
3. Push images to the cloud registry.
4. Authenticate to Kubernetes.
5. Deploy the application.

---

# Teardown

Destroy layers in reverse dependency order:

```text
40-cicd
   ↓
30-data
   ↓
20-platform
   ↓
10-network
```

For example:

```bash
terraform destroy
```

inside:

```text
40-cicd
30-data
20-platform
10-network
```

**Be extremely careful with `30-data`.**

It contains persistent resources such as:

```text
PostgreSQL
Object storage
Redis
Kafka/Event Hubs
Secrets
```

Destroying these resources may result in permanent data loss depending on the cloud resource configuration.

Read the cloud-specific README before destroying an environment.

---

# Important Design Decisions

## 1. Cloud-independent application deployment

The application is not deployed by Terraform.

This allows the same Helm/Kubernetes deployment model to run on:

```text
AKS
EKS
```

while Terraform handles the cloud-specific infrastructure.

---

## 2. Cloud-native managed services

Where practical, each cloud uses its managed services:

```text
Azure:
PostgreSQL → Flexible Server
Redis      → Azure Cache
Kafka      → Event Hubs
Storage    → Blob
Secrets    → Key Vault

AWS:
Postgres   → RDS
Redis      → ElastiCache
Kafka      → MSK / self-hosted
Storage    → S3
Secrets    → Secrets Manager
```

This reduces the amount of stateful infrastructure that must be operated inside Kubernetes.

---

## 3. Self-hosted infrastructure remains portable

RabbitMQ and Elasticsearch remain in Kubernetes.

This keeps them independent of a specific cloud provider and allows the same Kubernetes configuration to be reused across Azure and AWS.

---

## 4. Kafka is configurable on AWS

AWS supports two deployment modes:

```text
kafka_mode = "self-hosted"
```

or:

```text
kafka_mode = "msk"
```

Self-hosted Kafka:

```text
EKS
└── Kafka
```

Managed Kafka:

```text
AWS
└── MSK
```

The AWS README documents the application configuration required for switching between them.

Azure uses:

```text
Event Hubs
```

as its Kafka-compatible managed service.

---

# Security Notes

This Terraform tree is designed to demonstrate a cloud-native production-oriented architecture, but individual environments may still require additional hardening.

Important areas include:

### Network security

Review:

```text
CIDR allow-lists
Public endpoints
Private endpoints
Security groups / NSGs
NAT
Ingress
Egress
```

### Identity

Prefer:

```text
OIDC
Workload Identity
IRSA
Azure RBAC
```

over long-lived credentials.

### Secrets

Secrets should be stored in:

```text
Azure Key Vault
AWS Secrets Manager
```

but remember that Terraform state may also contain generated secret values.

### Kubernetes

Use:

```text
least-privilege RBAC
network policies
resource limits
pod security controls
image scanning
```

for production environments.

---

# Quick Reference

## Layer order

```text
10-network
     ↓
20-platform
     ↓
30-data

40-cicd
     ↓
after 20-platform
```

## Azure

```text
AKS
ACR
PostgreSQL Flexible Server
Azure Cache for Redis
Event Hubs
Blob Storage
Key Vault
Log Analytics
```

Documentation:

[`azure/README.md`](azure/README.md)

---

## AWS

```text
EKS
ECR
RDS PostgreSQL
ElastiCache
MSK / self-hosted Kafka
S3
Secrets Manager
CloudWatch
```

Documentation:

[`aws/README.md`](aws/README.md)

---

## Application deployment

Terraform:

```text
Cloud infrastructure
        ↓
Kubernetes cluster
        ↓
READY
```

Then:

```text
Helm / Kustomize
        ↓
Application workloads
```

Application infrastructure:

```text
infra/
├── terraform/     ← Cloud infrastructure
├── helm/          ← Application deployment
└── k8s/           ← Kubernetes resources
```

---

# Summary

The Terraform architecture intentionally follows three principles:

### 1. Separate clouds

```text
Azure ──┐
        ├── independent Terraform trees
AWS ────┘
```

### 2. Separate infrastructure layers

```text
Network
   ↓
Platform
   ↓
Data

CI/CD
```

Each layer has independent state and a limited responsibility.

### 3. Separate infrastructure from application deployment

```text
Terraform
   │
   ▼
Cloud + Kubernetes infrastructure
   │
   ▼
Helm / Kubernetes
   │
   ▼
Healthcare application
```

This makes the infrastructure reproducible, keeps cloud-specific concerns isolated, and allows the same application deployment model to run across both Azure and AWS.
