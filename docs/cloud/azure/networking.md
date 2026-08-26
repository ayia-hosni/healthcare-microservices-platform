# Azure Networking

> Part of [Azure Architecture](README.md). Covers the API/ingress path, VNet layout, egress,
> and network-security controls.

---

## Table of Contents

- [API & Ingress](#api--ingress)
- [Production Edge](#production-edge)
- [Networking](#networking)
- [NAT Gateway](#nat-gateway)
- [Network Security](#network-security)

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

---

# Production Edge

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

# NAT Gateway

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

# Network Security

The architecture combines:

* VNet isolation
* NSGs
* Kubernetes network policies
* controlled ingress
* controlled egress
* firewall rules
* managed identity

The goal is to establish explicit trust boundaries between application,
platform, and external resources. See
[Identity & Security](identity-security.md) for the identity side of that
boundary.
