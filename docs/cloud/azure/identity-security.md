# Azure Identity & Security

> Part of [Azure Architecture](README.md). Covers Key Vault, workload identity, least-privilege
> permission scoping, and the platform's defense-in-depth security model.

---

## Table of Contents

- [Secret Flow](#secret-flow)
- [Workload Identity](#workload-identity)
- [Least Privilege](#least-privilege)
- [Security Architecture](#security-architecture)
- [Security Controls](#security-controls)

---

# Secret Flow

Azure Key Vault is the central secret-management system.

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

# Workload Identity

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

# Least Privilege

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
