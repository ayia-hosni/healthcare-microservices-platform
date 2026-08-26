# AWS Networking

> Part of [AWS Architecture](README.md). 🏗️ Designed, not deployed — see the banner there.
> Covers the API/ingress path, VPC layout, egress, and network-security controls.

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
Route 53
  │
  ▼
ACM (TLS)
  │
  ▼
Application Load Balancer
  │
  ▼
AWS Load Balancer Controller
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

Ingress is responsible for HTTP routing, TLS termination, request
forwarding, health-aware routing, and centralized edge policies.
Application services remain unaware of the external load-balancing
implementation. The routing rules themselves — one path per service — are
already defined declaratively in `infra/k8s/base/ingress.yaml`, the same
manifest used against Minikube's `ingress-nginx` addon in local development
and against AKS today; adapting it to AWS Load Balancer Controller
annotations (or keeping NGINX behind an AWS load balancer, if ingress
portability is prioritized over native integration) is what turns that
routing table into a reachable AWS edge.

---

# Production Edge

```text
Internet
   │
   ▼
Amazon Route 53
   │
   ▼
AWS WAF
   │
   ▼
Application Load Balancer
   │
   ▼
EKS Ingress
```

Certificates are issued and renewed automatically through AWS Certificate
Manager rather than managed manually.

---

# Networking

The AWS network is organized around private application infrastructure
and controlled external access.

```text
                         Internet
                            │
                            ▼
                     Public Subnets
                            │
                            ▼
                       Amazon VPC
                            │
                     ┌──────┴──────┐
                     │             │
                     ▼             ▼
                EKS Subnet    ALB / Public Edge
                     │
                     ▼
               Application Pods
                     │
                     ▼
                NAT Gateway
                     │
          ┌──────────┼──────────┐
          ▼          ▼          ▼
       PostgreSQL  Redis      MSK
```

Public subnets host the ALB and NAT Gateway; private subnets host EKS
worker nodes, RDS, ElastiCache, and MSK. No public access should be granted
to PostgreSQL, Redis, Kafka, RabbitMQ, Elasticsearch, or Kubernetes worker
nodes.

---

# NAT Gateway

```text
EKS Pod
   │
   ▼
EKS Subnet
   │
   ▼
NAT Gateway
   │
   ▼
Static Public IP
   │
   ▼
AWS Services / External APIs
```

The static egress address can be used for controlled firewall allow-lists
and external integrations. For services reachable over AWS's private
network — S3, ECR, CloudWatch, Secrets Manager — VPC endpoints reduce NAT
traffic and improve security further.

---

# Network Security

The architecture combines VPC isolation, security groups, Kubernetes
network policies, controlled ingress/egress, firewall rules, and IAM roles
for service accounts. The goal is to establish explicit trust boundaries
between application, platform, and external resources. See
[Identity & Security](identity-security.md) for the identity side of that
boundary.
