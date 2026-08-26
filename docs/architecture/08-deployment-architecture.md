# Deployment Architecture

The platform is packaged as independently deployable containers and runs on Kubernetes. The repository provides **Docker Compose for local development**, **Kustomize for Kubernetes deployment**, **Helm for centralized deployment configuration**, and **ArgoCD for GitOps-based delivery**.

The currently exercised Kubernetes path is **Minikube**, driven by [`infra/k8s/scripts/deploy-to-minikube.sh`](../../infra/k8s/scripts/deploy-to-minikube.sh).

---

## Table of Contents

1. [Deployment Overview](#1-deployment-overview)
2. [Kubernetes Deployment](#2-kubernetes-deployment)
3. [Application Workloads](#3-application-workloads)
4. [Infrastructure Services](#4-infrastructure-services)
5. [Kustomize Structure](#5-kustomize-structure)
6. [Helm Deployment](#6-helm-deployment)
7. [Deployment Paths](#7-deployment-paths)
8. [GitOps with ArgoCD](#8-gitops-with-argocd)
9. [Configuration & Secrets](#9-configuration--secrets)
10. [Health & Resource Management](#10-health--resource-management)
11. [Environment Strategy](#11-environment-strategy)
12. [Cloud Deployment](#12-cloud-deployment)
13. [Production Deployment Direction](#13-production-deployment-direction)
14. [Related Documentation](#14-related-documentation)

---

# 1. Deployment Overview

```text
                         SOURCE CODE
                              │
                              ▼
                     ┌─────────────────┐
                     │     Git Repo    │
                     └────────┬────────┘
                              │
                ┌─────────────┼─────────────┐
                │             │             │
                ▼             ▼             ▼
          Docker Compose   Kustomize       Helm
                │             │             │
                ▼             ▼             ▼
             Local        Kubernetes    Kubernetes
           Development      Dev Path      Deployment
                              │
                              ▼
                         ┌───────────┐
                         │ Minikube  │
                         └─────┬─────┘
                               │
                               ▼
                         NGINX Ingress
                               │
                               ▼
                     Healthcare Platform
```

The deployment model separates:

* **Application workloads** — Spring Boot services, GraphQL gateway and frontend.
* **Infrastructure dependencies** — PostgreSQL, Kafka, RabbitMQ, Redis and MinIO.
* **Edge routing** — NGINX Ingress.
* **Configuration** — ConfigMaps and Secrets.
* **Deployment configuration** — Kustomize and Helm.
* **Delivery automation** — ArgoCD.

---

# 2. Kubernetes Deployment

The Kubernetes architecture can be viewed as a set of layers:

```text
╔════════════════════════════════════════════════════════════╗
║                         CLIENTS                            ║
║                                                            ║
║                    Browser / Internet                      ║
╚══════════════════════════════╤═════════════════════════════╝
                               │
                               ▼
╔════════════════════════════════════════════════════════════╗
║                         EDGE                               ║
║                                                            ║
║                NGINX Ingress Controller                    ║
╚══════════════════════════════╤═════════════════════════════╝
                               │
                               ▼
┌────────────────────────────────────────────────────────────┐
│                    APPLICATION LAYER                       │
│                                                            │
│  Frontend                                                   │
│  GraphQL Gateway                                            │
│  Identity                                                   │
│  Patient                                                    │
│  Doctor                                                     │
│  Appointment                                                │
│  EMR                                                        │
│  Billing                                                    │
│  Notification                                               │
│  Audit                                                      │
│  Analytics                                                  │
└────────────────────────────┬───────────────────────────────┘
                             │
              ┌──────────────┼───────────────┐
              ▼              ▼               ▼
┌────────────────────┐ ┌─────────────┐ ┌────────────────────┐
│ Messaging          │ │ Caching     │ │ Object Storage     │
│                    │ │             │ │                    │
│ Kafka              │ │ Redis       │ │ MinIO              │
│ RabbitMQ           │ │             │ │                    │
└─────────┬──────────┘ └─────────────┘ └────────────────────┘
          │
          ▼
┌────────────────────────────────────────────────────────────┐
│                      DATA LAYER                            │
│                                                            │
│                       PostgreSQL                            │
└────────────────────────────────────────────────────────────┘
```

All workloads are deployed into the:

```text
healthcare-platform
        │
        ▼
Kubernetes Namespace
```

---

# 3. Application Workloads

The application layer contains independently deployable workloads.

```text
                     NGINX INGRESS
                           │
        ┌──────────────────┼───────────────────┐
        │                  │                   │
        ▼                  ▼                   ▼
    Frontend          GraphQL Gateway      REST APIs
                                               │
              ┌────────────────────────────────┼───────────────┐
              │                │                │               │
              ▼                ▼                ▼               ▼
          Identity          Patient          Doctor        Appointment
              │                │                │               │
              └────────────────┴────────────────┴───────────────┘
                                               │
                                               ▼
                                    ┌─────────────────────┐
                                    │ Internal Services   │
                                    │                     │
                                    │ EMR                 │
                                    │ Billing             │
                                    │ Notification        │
                                    │ Audit               │
                                    │ Analytics           │
                                    └─────────────────────┘
```

Each Spring Boot service is deployed independently:

```text
Service
   │
   ├── Deployment
   ├── Service
   ├── ConfigMap / configuration
   ├── Secret references
   ├── Health probes
   └── Resource requests / limits
```

This allows services to be restarted, updated and scaled independently.

---

# 4. Infrastructure Services

The development Kubernetes environment includes the infrastructure required to run the platform locally.

```text
                 APPLICATION SERVICES
                         │
        ┌────────────────┼────────────────┐
        │                │                │
        ▼                ▼                ▼
     PostgreSQL        Kafka           Redis
        │                │
        │                ▼
        │             Consumers
        │                │
        │                ▼
        │           RabbitMQ
        │                │
        │                ▼
        └────────────── MinIO
```

### Development infrastructure

| Component           | Kubernetes role                                |
| ------------------- | ---------------------------------------------- |
| PostgreSQL          | Transactional persistence                      |
| Kafka               | Cross-service event backbone                   |
| Zookeeper           | Kafka dependency in the current dev deployment |
| RabbitMQ            | Notification retry/DLQ processing              |
| Redis               | Application read caching                       |
| MinIO               | Object/document storage                        |
| WireMock payer mock | Simulated external payer                       |
| Prometheus          | Metrics collection                             |
| Grafana             | Metrics visualization                          |
| Zipkin              | Distributed tracing                            |

The development overlay supplies infrastructure that would typically be replaced by managed services or dedicated infrastructure in a production cloud environment.

---

# 5. Kustomize Structure

The Kubernetes manifests are organized using a **base + environment overlay** model.

```text
infra/k8s/
│
├── base/
│   │
│   ├── namespace.yaml
│   ├── ingress.yaml
│   ├── deployments/
│   ├── services/
│   ├── configmaps/
│   ├── secrets/
│   └── ...
│
└── overlays/
    │
    └── dev/
        │
        ├── datastores/
        │   ├── postgres
        │   ├── redis
        │   ├── kafka
        │   ├── zookeeper
        │   ├── rabbitmq
        │   ├── minio
        │   └── payer-mock
        │
        ├── patches/
        │
        └── kustomization.yaml
```

The relationship is:

```text
                  Kustomize Base
                       │
                       │
                       ▼
              Application Workloads
                       │
                       ▼
                 ┌───────────┐
                 │    DEV    │
                 │  Overlay  │
                 └─────┬─────┘
                       │
          ┌────────────┼────────────┐
          ▼            ▼            ▼
      Datastores     Patches     Dev Config
          │
          ▼
     Complete Dev
      Deployment
```

### Base

The base describes the application platform without assuming that development-only infrastructure is running inside the same cluster.

### Development overlay

The `dev` overlay adds:

* PostgreSQL
* Redis
* Kafka
* Zookeeper
* RabbitMQ
* MinIO
* WireMock payer
* development-specific patches
* development configuration

This makes the same application manifests reusable across environments.

---

# 6. Helm Deployment

The repository also provides an umbrella Helm chart:

```text
infra/helm/healthcare-platform/
│
├── Chart.yaml
├── values.yaml
├── templates/
│   ├── deployments/
│   ├── services/
│   ├── configmaps/
│   ├── secrets/
│   └── ...
└── ...
```

The deployment model is:

```text
                    Helm Chart
                        │
                        ▼
                  values.yaml
                        │
            ┌───────────┼───────────┐
            ▼           ▼           ▼
        Service A   Service B   Service C
            │           │           │
            └───────────┼───────────┘
                        ▼
                  Kubernetes
```

Helm provides centralized, parameterized deployment configuration while Kustomize provides the repository's base/overlay model.

The two approaches serve different operational workflows rather than representing separate application architectures.

---

# 7. Deployment Paths

The repository supports multiple deployment paths.

```text
                         Application Code
                               │
                               ▼
                     ┌──────────────────┐
                     │ Docker Images    │
                     └────────┬─────────┘
                              │
             ┌────────────────┼────────────────┐
             │                │                │
             ▼                ▼                ▼
       Docker Compose      Kustomize          Helm
             │                │                │
             ▼                ▼                ▼
        Local Docker       Minikube       Kubernetes
             │                │                │
             │                ▼                ▼
             │          Development       Cloud / Cluster
             │
             ▼
      Developer Environment
```

### Docker Compose

Used for local development and integration testing of the complete platform stack.

### Minikube

The Kubernetes path currently exercised end-to-end.

```text
deploy-to-minikube.sh
        │
        ├── Build backend images
        ├── Build frontend
        ├── Load images into Minikube
        └── Apply dev overlay
                    │
                    ▼
              Minikube Cluster
```

### Cloud Kubernetes

The manifests are structured to support deployment to a real Kubernetes cluster, with infrastructure progressively replaced by managed equivalents.

---

# 8. GitOps with ArgoCD

ArgoCD provides a GitOps deployment path over the same Kubernetes manifests.

```text
                         Git Repository
                               │
                               │
                               ▼
                    infra/k8s/overlays/dev
                               │
                               ▼
                         ┌───────────┐
                         │  ArgoCD   │
                         └─────┬─────┘
                               │
                         Sync desired
                            state
                               │
                               ▼
                    ┌────────────────────┐
                    │ Kubernetes Cluster │
                    │                    │
                    │ healthcare-platform│
                    └─────────┬──────────┘
                              │
                              ▼
                        Running Pods
```

The operational model is:

```text
Developer
    │
    ▼
Git Commit / Pull Request
    │
    ▼
main
    │
    ▼
ArgoCD detects desired-state change
    │
    ▼
Kustomize dev overlay
    │
    ▼
Kubernetes
    │
    ▼
Deployment reconciled
```

ArgoCD continuously compares:

```text
Desired State in Git
          │
          │ compare
          ▼
Actual State in Cluster
          │
          ▼
      Reconcile
```

Manual cluster drift can therefore be detected and reconciled back toward the Git-defined state.

The current repository contains a **development ArgoCD application**. Separate staging and production applications can be introduced when those environment overlays exist.

---

# 9. Configuration & Secrets

Application configuration is externalized from container images.

```text
                 Kubernetes
                     │
          ┌──────────┴──────────┐
          ▼                     ▼
      ConfigMap              Secret
          │                     │
          │                     │
          ▼                     ▼
   Non-sensitive            Sensitive
   configuration             values
          │                     │
          └──────────┬──────────┘
                     ▼
                Application
                     │
                     ▼
                 Spring Boot
```

Examples include:

* database configuration
* Kafka configuration
* RabbitMQ configuration
* Redis configuration
* MinIO configuration
* payer eligibility endpoint
* application-specific settings

The development environment explicitly overrides the payer endpoint:

```text
billing-service
      │
      ▼
PAYER_ELIGIBILITY_URL
      │
      ▼
WireMock payer-mock
```

This prevents local development from requiring a real insurance trading partner.

Production deployments should replace development credentials and endpoints with managed secrets and environment-specific configuration.

---

# 10. Health & Resource Management

Each Spring Boot service exposes Actuator health information.

```text
                    Kubernetes
                        │
             ┌──────────┴──────────┐
             │                     │
             ▼                     ▼
         Readiness              Liveness
             │                     │
             ▼                     ▼
       "Can receive?"          "Is alive?"
             │                     │
             └──────────┬──────────┘
                        ▼
                  Spring Boot
                     Actuator
```

The Kubernetes deployment therefore follows:

```text
Pod Starts
   │
   ▼
Liveness Probe
   │
   ▼
Readiness Probe
   │
   ▼
Ready
   │
   ▼
Receives Traffic
```

Application containers also define resource requests and limits:

```text
                    Pod
                     │
           ┌─────────┴─────────┐
           ▼                   ▼
       CPU Request          Memory Request
           │                   │
           └─────────┬─────────┘
                     ▼
               Kubernetes
                Scheduler
```

This gives local Minikube deployments behavior closer to a production Kubernetes workload.

---

# 11. Environment Strategy

The repository currently follows an environment-overlay model.

```text
                       Base
                        │
             ┌──────────┼──────────┐
             ▼          ▼          ▼
            Dev       Staging      Prod
             │
             ▼
        Minikube / Dev
```

The current repository has:

```text
base
  │
  └── dev
```

The structure is intentionally extensible:

```text
infra/k8s/
├── base/
└── overlays/
    ├── dev/
    ├── staging/   ← environment can be added
    └── prod/      ← environment can be added
```

Environment-specific differences should remain in overlays or deployment configuration rather than being hard-coded into application manifests.

---

# 12. Cloud Deployment

The repository also contains Terraform infrastructure under:

```text
infra/terraform/
```

The cloud deployment model is:

```text
                    Terraform
                        │
                        ▼
                 Cloud Resources
                        │
          ┌─────────────┼─────────────┐
          ▼             ▼             ▼
       Network      Kubernetes     Managed
                    Cluster        Services
                        │
                        ▼
                  Application
                  Deployments
```

The repository includes an AKS/Terraform development environment.

The important distinction is that **the Minikube path is the currently exercised Kubernetes deployment loop**, while the cloud infrastructure represents the infrastructure-as-code path toward cloud deployment.

The architecture therefore separates:

```text
Application Deployment
        │
        ▼
   Kubernetes
        │
        ▼
Cloud Infrastructure
        │
        ▼
     Terraform
```

from the local development path:

```text
Docker Images
     │
     ▼
  Minikube
     │
     ▼
Kustomize
```

---

# 13. Production Deployment Direction

The deployment architecture is designed to evolve from local development toward a production Kubernetes platform.

```text
┌──────────────────────────────────────────────────────────┐
│                    DEVELOPMENT                           │
│                                                          │
│ Docker Compose                                           │
│ Minikube                                                  │
│ In-cluster PostgreSQL / Kafka / Redis / RabbitMQ / MinIO│
│ WireMock payer                                            │
└───────────────────────────┬──────────────────────────────┘
                            │
                            ▼
┌──────────────────────────────────────────────────────────┐
│                    CLOUD DEV / STAGING                    │
│                                                          │
│ Managed Kubernetes                                       │
│ Managed PostgreSQL                                       │
│ Managed Kafka                                             │
│ Managed secrets                                           │
│ Environment-specific configuration                       │
│ ArgoCD GitOps                                             │
└───────────────────────────┬──────────────────────────────┘
                            │
                            ▼
┌──────────────────────────────────────────────────────────┐
│                    PRODUCTION                             │
│                                                          │
│ Highly available Kubernetes                              │
│ Managed databases / messaging                             │
│ Production secrets management                             │
│ Network policies                                          │
│ Autoscaling                                               │
│ Observability                                             │
│ External payer integration                                │
│ Multi-environment GitOps                                  │
└──────────────────────────────────────────────────────────┘
```

The major production evolution points are:

| Area                  | Deployment direction                                         |
| --------------------- | ------------------------------------------------------------ |
| Kubernetes            | Minikube → managed Kubernetes                                |
| PostgreSQL            | In-cluster → managed PostgreSQL                              |
| Kafka                 | Dev Kafka → managed/production Kafka                         |
| Secrets               | Kubernetes/dev configuration → managed secret store          |
| Networking            | Default cluster networking → explicit network policies       |
| Scaling               | Fixed development replicas → autoscaling                     |
| Availability          | Single development environment → highly available deployment |
| Delivery              | Manual/Kustomize → ArgoCD GitOps across environments         |
| Observability         | Dev monitoring → production metrics, logs and traces         |
| External integrations | WireMock → real payer integration                            |
| Environments          | Dev → Dev / Staging / Production                             |

---

# 14. Related Documentation

| Document                                                       | Focus                                              |
| -------------------------------------------------------------- | -------------------------------------------------- |
| [System Context](01-system-context.md)                         | External actors and system boundaries              |
| [Container Architecture](02-container-architecture.md)         | Runtime containers                                 |
| [Service Architecture](03-service-architecture.md)             | Service responsibilities                           |
| [Communication Architecture](04-communication-architecture.md) | REST, gRPC, Kafka, RabbitMQ and SOAP               |
| [Event Topology](05-event-topology.md)                         | Kafka event flows                                  |
| [Data Architecture](06-data-architecture.md)                   | Persistence and data ownership                     |
| [Security Architecture](07-security-architecture.md)           | Authentication, authorization and trust boundaries |
| [Observability Architecture](09-observability-architecture.md) | Metrics, health and tracing                        |
| [ADR-0001](../adr/0001-api-gateway-ingress.md)                 | API gateway / Ingress decision                     |
| [ADR-0005](../adr/0005-gitops-with-argocd.md)                  | ArgoCD GitOps decision                             |

> **Deployment Architecture:** The platform is containerized and Kubernetes-ready, with Minikube providing the exercised development deployment, Kustomize providing environment overlays, Helm providing centralized deployment configuration, Terraform providing cloud infrastructure, and ArgoCD providing GitOps reconciliation. The deployment model deliberately separates application workloads from infrastructure dependencies so development infrastructure can progressively be replaced by managed production services without changing the service architecture.
