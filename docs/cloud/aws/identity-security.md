# AWS Identity & Security

> Part of [AWS Architecture](README.md). 🏗️ Designed, not deployed — see the banner there.
> Covers Secrets Manager, IAM Roles for Service Accounts (IRSA), least-privilege permission
> scoping, and the platform's defense-in-depth security model.

---

## Table of Contents

- [Secret Flow](#secret-flow)
- [Workload Identity](#workload-identity)
- [Least Privilege](#least-privilege)
- [Security Architecture](#security-architecture)
- [Security Controls](#security-controls)

---

# Secret Flow

AWS Secrets Manager is the central secret-management system.

```text
                      AWS Secrets Manager
                               │
               ┌───────────────┼───────────────┐
               ▼               ▼               ▼
          JWT Secret      DB Credentials    Service Secrets
               │               │               │
               └───────────────┼───────────────┘
                               │
                               ▼
                   IAM Roles for Service Accounts
                               │
                               ▼
                              EKS
```

---

# Workload Identity

Pods authenticate through the EKS OIDC issuer.

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
IAM Role (IRSA)
 │
 ▼
Secrets Manager / AWS APIs
```

This eliminates long-lived cloud credentials from application containers.
IRSA covers workload access to AWS APIs such as Secrets Manager and S3 —
ECR image pulls are handled separately by the EKS node IAM role, so
application pods don't need their own ECR credentials.

---

# Least Privilege

Identity permissions should be scoped to the workload.

```text
identity-service
      │
      └── identity-related secrets

emr-service
      │
      └── read/write s3://bucket/emr/

analytics-service
      │
      └── analytics resources
```

The same principle applies to storage, messaging, and cloud APIs.

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

See [Networking](networking.md) for the WAF/ingress/network-policy layers
this builds on.

---

# Security Controls

### Identity

* AWS IAM / SSO
* EKS Access Entries
* IAM Roles for Service Accounts
* Least-privilege permissions

### Network

* VPC isolation
* Security groups
* Cilium / Kubernetes network policies
* controlled egress
* firewall allow-lists
* TLS

### Secrets

* AWS Secrets Manager
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
* IAM roles for service accounts

For real healthcare/PHI workloads, a production AWS implementation
additionally requires a formal compliance review and AWS service
eligibility assessment — this document is an architecture specification,
not a compliance certification.
