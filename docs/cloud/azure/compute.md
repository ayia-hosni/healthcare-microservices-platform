# Azure Compute — AKS

> Part of [Azure Architecture](README.md). Covers the AKS cluster layout, networking
> internals, scaling, infrastructure as code, and CI/CD — the "how it's built and how it runs"
> layer underneath the workloads described in the main doc.

---

## Table of Contents

- [Cluster Layout](#cluster-layout)
- [Azure CNI Overlay](#azure-cni-overlay)
- [Cilium](#cilium)
- [Node Pool Strategy](#node-pool-strategy)
- [Availability & Scaling](#availability--scaling)
- [Infrastructure as Code](#infrastructure-as-code)
- [CI/CD & Deployment](#cicd--deployment)

---

# Cluster Layout

Azure Kubernetes Service provides the application runtime.

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
cluster workloads while managed Azure services (see
[Data Services](data-services.md)) handle the core persistent platform
dependencies.

---

# Azure CNI Overlay

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

# Cilium

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

# Node Pool Strategy

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
resource requirements of the entire platform. Database scaling is covered
in [Data Services](data-services.md#production-database-evolution).

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

See [`../../infrastructure/gitops.md`](../../infrastructure/gitops.md) for how the ArgoCD reconcile
loop actually works today, and its known limitations.
