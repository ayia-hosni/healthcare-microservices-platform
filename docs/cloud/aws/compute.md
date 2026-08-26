# AWS Compute — EKS

> Part of [AWS Architecture](README.md). 🏗️ Designed, not deployed — see the banner there.
> Covers the EKS cluster layout, networking internals, scaling, infrastructure as code, and
> CI/CD.

---

## Table of Contents

- [Cluster Layout](#cluster-layout)
- [VPC CNI](#vpc-cni)
- [Cilium (optional)](#cilium-optional)
- [Node Group Strategy](#node-group-strategy)
- [Availability & Scaling](#availability--scaling)
- [Infrastructure as Code](#infrastructure-as-code)
- [CI/CD & Deployment](#cicd--deployment)

---

# Cluster Layout

Amazon Elastic Kubernetes Service provides the application runtime.

```text
EKS Cluster
│
├── System Node Group
│   ├── CoreDNS
│   ├── VPC CNI
│   ├── Metrics components
│   └── Kubernetes add-ons
│
└── Application Node Group
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
cluster workloads while managed AWS services (see
[Data Services](data-services.md)) handle the core persistent platform
dependencies.

---

# VPC CNI

The cluster uses the Amazon VPC CNI plugin, assigning pods addresses
directly out of VPC subnet ranges.

```text
Amazon VPC
10.20.0.0/16
      │
      └── EKS Subnet
           10.20.0.0/20
                │
                └── Pod ENIs draw from the same subnet range
```

This keeps pod networking natively routable inside the VPC without an
overlay layer, at the cost of consuming more VPC IP address space per node
than an overlay-based CNI would.

---

# Cilium (optional)

Cilium can run on top of the VPC CNI (chained mode) to add Kubernetes
network policy enforcement beyond AWS security groups.

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

# Node Group Strategy

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

This allows individual workload classes to evolve independently. A cost-
constrained deployment can start with only the system node group and add
the application node group once capacity requirements justify separating
system and application workloads.

---

# Availability & Scaling

```text
Incoming Traffic
       │
       ▼
Application Load Balancer
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
Cluster Autoscaler / Karpenter
       │
       ▼
EKS Nodes
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

Terraform manages the AWS platform, following the same module/environment
structure as the existing Azure Terraform.

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
        ├── vpc.tf
        ├── eks.tf
        ├── rds.tf
        ├── elasticache.tf
        ├── msk.tf
        ├── secretsmanager.tf
        ├── ecr.tf
        ├── s3.tf
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
Amazon S3 (+ state locking)
    │
    ▼
Terraform State
```

This enables state persistence, team collaboration, locking, versioning,
and disaster recovery of state. The backend should be bootstrapped before
the main infrastructure is deployed, and state should never be committed
to Git.

---

# CI/CD & Deployment

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
    └── Push to ECR
             │
             ▼
        Deployment
             │
             ▼
            EKS
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
Container Registry (ECR)
 │
 ▼
GitOps Repository
 │
 ▼
Argo CD
 │
 ▼
EKS
```

This provides auditable deployments, declarative configuration, automatic
reconciliation, rollback through Git, and environment consistency — see
[`../../infrastructure/gitops.md`](../../infrastructure/gitops.md) for how the ArgoCD reconcile
loop works on the real (Azure) deployment today.
