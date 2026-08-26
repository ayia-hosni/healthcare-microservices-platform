# 📚 Documentation Index

Welcome to the Healthcare Platform documentation. This repository contains architecture,
development, infrastructure, cloud, and operational documentation. [`README.md`](README.md) is
the front door; this page is the full map.

---

## Start Here

Choose the path that best matches your role.

### 👔 Recruiters & Hiring Managers

A quick overview of the project, engineering scope, and technologies.

1. [Project Overview](README.md)
2. [Architecture Overview](docs/architecture/README.md)
3. [Cloud Architecture](docs/cloud/README.md)
4. [Project Progress](PROGRESS.md)

### 👨‍💻 Backend Developers

Learn how the application is structured.

1. [Architecture Overview](docs/architecture/README.md)
2. [Application Architecture](docs/architecture/application-architecture.md)
3. [Microservices](docs/architecture/microservices.md)
4. [Service Communication](docs/architecture/communication.md)
5. [Data Architecture](docs/architecture/data-architecture.md)
6. [Local Development](docs/development/local-development.md)

### ☁️ Cloud & DevOps Engineers

Explore the infrastructure and deployment architecture.

1. [Cloud Overview](docs/cloud/README.md)
2. [Azure Architecture](docs/cloud/azure/README.md)
3. [AWS Architecture](docs/cloud/aws/README.md)
4. [Kubernetes](docs/infrastructure/kubernetes.md)
5. [Terraform](docs/infrastructure/terraform.md)
6. [GitOps](docs/infrastructure/gitops.md)
7. [Observability](docs/operations/observability.md)

---

## Documentation Map

### 🏗 Architecture

| Document | Description |
| --- | --- |
| [Architecture Overview](docs/architecture/README.md) | High-level system architecture and technology stack |
| [Application Architecture](docs/architecture/application-architecture.md) | Backend application structure |
| [Microservices](docs/architecture/microservices.md) | Service responsibilities and boundaries |
| [Service Communication](docs/architecture/communication.md) | REST, GraphQL, gRPC, events, and queues |
| [Data Architecture](docs/architecture/data-architecture.md) | PostgreSQL, Redis, storage, and ownership |
| [`docs/architecture/00-10*.md`](docs/architecture/00-index.md) | The original C4-style deep-dive series (system context → deployment → observability → the appointment-booking sequence) — the pages above summarize and link into this series rather than duplicating it |
| [`docs/adr/`](docs/adr/) | Architecture Decision Records |

### ☁️ Cloud

| Document | Description |
| --- | --- |
| [Cloud Overview](docs/cloud/README.md) | Multi-cloud architecture overview |
| [Azure Architecture](docs/cloud/azure/README.md) | Azure implementation — real, provisioned |
| [AWS Architecture](docs/cloud/aws/README.md) | AWS implementation — designed, not deployed |

### 🛠 Infrastructure

| Document | Description |
| --- | --- |
| [Kubernetes](docs/infrastructure/kubernetes.md) | Kustomize deployments for Minikube and AKS |
| [Terraform](docs/infrastructure/terraform.md) | Infrastructure as Code for Azure |
| [GitOps](docs/infrastructure/gitops.md) | Argo CD and deployment flow |
| [`infra/k8s/README.md`](infra/k8s/README.md) | Kubernetes manifest layout in detail |
| [`infra/terraform/README.md`](infra/terraform/README.md) | Terraform module layout in detail |
| [`infra/argocd/README.md`](infra/argocd/README.md) | Argo CD configuration in detail |

### 💻 Development

| Document | Description |
| --- | --- |
| [Local Development](docs/development/local-development.md) | The four ways to run it: Docker Compose, Minikube, a hybrid dev loop, or fully native |
| [`frontend/README.md`](frontend/README.md) | The Angular SPA |
| Per-service READMEs | See the [Services table](README.md#-services) in the root README — each service name links to its own README |

### 📊 Operations

| Document | Description |
| --- | --- |
| [Observability](docs/operations/observability.md) | Logs, metrics, and traces |
| [`docs/operations/README.md`](docs/operations/README.md) | Docker Compose, Kubernetes/Kustomize, Helm, endpoint reference, CI/CD |
| [`docs/infrastructure/gitops.md`](docs/infrastructure/gitops.md) | How the Argo CD reconcile loop works, and its known limitations |
| [`docs/reliability/README.md`](docs/reliability/README.md) | Transactional outbox, rate limiting & circuit breakers, retry/DLQ |
| [`docs/architecture/supporting-services.md`](docs/architecture/supporting-services.md) | notification/audit/analytics-service's shared Kafka-consumer pattern |

### 📈 Project Status

| Document | Description |
| --- | --- |
| [`PROGRESS.md`](PROGRESS.md) | What's implemented vs. designed vs. planned, maturity breakdown, production roadmap |
| [`VISION.md`](VISION.md) | Target end-state product vision — clearly marked as not what's built today |

---

## Recommended Reading Order

```text
README.md
    │
    ▼
INDEX.md
    │
    ├── Recruiter Path
    │      │
    │      ├── Architecture Overview
    │      ├── Cloud Architecture
    │      └── Progress
    │
    ├── Developer Path
    │      │
    │      ├── Application Architecture
    │      ├── Microservices
    │      ├── Communication
    │      └── Data Architecture
    │
    └── DevOps Path
           │
           ├── Kubernetes
           ├── Terraform
           ├── Cloud Architecture
           └── Operations
```
