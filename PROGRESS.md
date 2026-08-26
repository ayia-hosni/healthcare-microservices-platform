# 📈 Progress & Roadmap

This file tracks where the platform actually stands — what's running, what's designed but not
built, and what's still on the roadmap toward a production-grade system. For the architecture
itself and how to run it, see [`README.md`](README.md).

The project distinguishes between:

- ✅ **Implemented** — real, running code
- 🏗️ **Designed** — documented, not yet built
- 📋 **Planned** — future roadmap, not yet designed in detail

---

## Table of Contents

* [What This Project Demonstrates](#what-this-project-demonstrates)
* [Distributed Systems & Reliability](#distributed-systems--reliability)
* [Designed, Not Yet Built](#designed-not-yet-built)
* [Architecture Maturity](#architecture-maturity)
* [Production Roadmap](#production-roadmap)
* [Project Status](#project-status)

---

## What This Project Demonstrates

| Area | Status |
|---|---|
| Java 21 + Spring Boot 3.3 | ✅ Implemented |
| Domain-oriented microservices (9 + BFF) | ✅ Implemented |
| Database-per-service (PostgreSQL) | ✅ Implemented |
| REST APIs | ✅ Implemented |
| GraphQL BFF | ✅ Implemented |
| gRPC contracts (`grpc-contracts`) | ✅ Implemented |
| Apache Kafka domain events | ✅ Implemented |
| **Transactional Outbox** (`common`'s `OutboxWriter`/`OutboxRelay`) | ✅ Implemented |
| RabbitMQ retry / DLQ processing | ✅ Implemented |
| Rate limiting (resilience4j, booking endpoint) | ✅ Implemented |
| Circuit breaker (resilience4j, payer eligibility call) | ✅ Implemented |
| Redis caching (patient/doctor lookups) | ✅ Implemented |
| MinIO object storage (EMR clinical documents) | ✅ Implemented |
| Idempotency keys + optimistic/pessimistic locking | ✅ Implemented |
| Docker Compose | ✅ Implemented |
| Kubernetes + Kustomize | ✅ Implemented |
| Helm chart (templated Deployment+Service per service) | ✅ Implemented |
| Prometheus metrics | ✅ Implemented |
| Grafana dashboards | ✅ Implemented |
| Zipkin distributed tracing | ✅ Implemented |
| Spring Boot Actuator health checks | ✅ Implemented |
| GitOps deployment via ArgoCD (see [`docs/infrastructure/gitops.md`](docs/infrastructure/gitops.md)) | ✅ Implemented |
| Elasticsearch (provisioned, no indexing consumers yet) | 🏗️ Partial |
| Bulkhead isolation | 🏗️ Designed |
| Orchestrated saga compensation | 🏗️ Designed |
| RS256 / JWKS authentication | 🏗️ Designed |
| Distributed / global rate limiting | 🏗️ Designed |
| OpenTelemetry migration | 🏗️ Designed |
| Horizontal Pod Autoscaling | 📋 Planned |
| Canary deployments | 📋 Planned |
| Multi-region disaster recovery | 📋 Planned |

---

## Distributed Systems & Reliability

| Problem                  | Current Approach                              | Production Evolution             |
| ------------------------- | ---------------------------------------------- | --------------------------------- |
| Duplicate requests        | Idempotency keys                              | Distributed idempotency store    |
| Concurrent booking        | Pessimistic locking + DB constraints          | Partition-aware concurrency      |
| Lost events                | ✅ Transactional Outbox (implemented)          | —                                 |
| Dependency failure         | ✅ Circuit breaker (payer eligibility)         | Extend to more call sites        |
| Traffic bursts             | ✅ Rate limiting (booking endpoint)            | Distributed/global rate limiting |
| Consumer failure           | Retry + DLQ (RabbitMQ)                        | Backoff + parking-lot strategy   |
| Cascading failures         | Service isolation                             | Bulkheads                        |
| Distributed transaction    | Event choreography                            | Saga compensation                |
| Traffic spikes (scaling)   | Kubernetes Deployments                        | HPA / KEDA autoscaling           |

---

## Designed, Not Yet Built

These are documented target architectures — not claims about what's deployed today.

### Authentication: RS256 / JWKS

Today's `identity-service` issues, and `common`'s `JwtVerifier` checks, a shared HS256
development secret (`Keys.hmacShaKeyFor`). The designed evolution:

```text
                    ┌──────────────────────┐
                    │ Identity Provider    │
                    │ Private Signing Key  │
                    └──────────┬───────────┘
                               │ RS256
                               ▼
                        ┌──────────────┐
                        │     JWKS     │
                        │ Public Keys  │
                        └──────┬───────┘
                               │
              ┌────────────────┼────────────────┐
              ▼                ▼                ▼
       Patient Service   Doctor Service   Appointment Service
              └──── Validate JWT Signature ────┘
```

Security roadmap: RS256/JWKS + key rotation, external secret management, Kubernetes
NetworkPolicies, service-to-service mTLS, distributed rate limiting, extended audit trails.

### Observability: OpenTelemetry

```text
Application ──► OpenTelemetry SDK ──► OTel Collector
                                            ├──── Metrics ────► Prometheus
                                            ├──── Traces ─────► Tempo / Jaeger
                                            └──── Logs ───────► Loki / Elasticsearch ──► Grafana
```

### Saga Compensation

The platform currently favors choreographed events where downstream services react
independently to `AppointmentCreated`/`AppointmentCancelled` — there is no orchestrator and no
automatic rollback if, say, billing fails after an appointment is booked.

```text
Appointment Created ──► Kafka ──► Billing Workflow
                                        │
                                   ┌────┴────┐
                                 Success   Failure
                                   │           │
                                   ▼           ▼
                               Continue   Compensation Event (not yet built)
                                                │
                                                ▼
                                       Reverse / Correct State
```

Compensation logic can be introduced where business workflows require explicit recovery from
partial failures.

### Production Target Architecture

The current repository runs locally via Docker Compose and Kubernetes/Minikube. This is a
possible production evolution, not a claim about what's deployed today:

```text
Internet ──► DNS / CDN / WAF ──► Load Balancer (TLS termination)
        ──► Kubernetes: NGINX Ingress ──► Application Pods / GraphQL BFF / Internal Services
        ──► Managed PostgreSQL (primary/replica) │ Kafka (multi-broker) │ Redis (HA)
        ──► OpenTelemetry ──► Collector ──► Metrics/Traces/Logs ──► Grafana
```

Two concrete cloud mappings of this shape exist: **Azure** is the real, provisioned one — see
[`docs/cloud/azure/README.md`](docs/cloud/azure/README.md) for how it's organized (AKS, Azure
Database for PostgreSQL, Azure Cache for Redis, Event Hubs, Key Vault, ACR, a Storage Account).
Standing up the ingress controller and migrating `emr-service` off the MinIO SDK onto the Azure
Blob SDK are the near-term items to reach a fully reachable deployment. **AWS** is a
designed-but-not-built alternative — see
[`docs/cloud/aws/README.md`](docs/cloud/aws/README.md) for the service-by-service mapping (EKS,
RDS, ElastiCache, MSK, Secrets Manager, ECR, S3) and the trade-offs carried over from the Azure
design.

---

## Architecture Maturity

```text
Foundation
████████████████████  Microservices
████████████████████  Database per service
████████████████████  Docker
████████████████████  Kubernetes

Communication
████████████████████  REST APIs
████████████████████  Kafka
████████████████████  gRPC
████████████████████  GraphQL BFF

Reliability
████████████████████  Transactional Outbox
██████████████████░░  Idempotency
██████████████████░░  Rate limiting / Circuit breaker (single call sites)
██████████████████░░  Retry / DLQ
████████████████░░░░  Distributed tracing
██████████░░░░░░░░░░  Bulkheads
████████░░░░░░░░░░░░  Saga compensation

Production Operations
██████████████████░░  Metrics
██████████████████░░  Health checks
████████████████░░░░  Kubernetes operations
████████████████░░░░  GitOps (dev environment only; image builds still manual — see docs/infrastructure/gitops.md)
████████░░░░░░░░░░░░  Progressive delivery
██████░░░░░░░░░░░░░░  Multi-region disaster recovery
```

**Legend:** `████████████████████` implemented and complete · `██████████████████░░`
implemented, actively used · `████████████████░░░░` partially implemented ·
`██████████░░░░░░░░░░` designed and documented · `██████░░░░░░░░░░░░░░` future roadmap.

---

## Production Roadmap

### Reliability

* [ ] Idempotent Kafka consumers / Inbox Pattern
* [ ] Exponential backoff policies
* [ ] Bulkheads
* [ ] Circuit breakers on remaining cross-service call sites
* [ ] Orchestrated saga compensation

### Security

* [ ] RS256 JWT signing + JWKS endpoint + key rotation
* [ ] External secret management
* [ ] Kubernetes NetworkPolicies
* [ ] mTLS between services
* [ ] Distributed / global rate limiting

### Kubernetes

* [ ] Horizontal Pod Autoscaling
* [ ] Pod Disruption Budgets
* [ ] Resource quotas
* [ ] KEDA event-driven scaling

### Observability

* [ ] OpenTelemetry
* [ ] Structured centralized logging
* [ ] Kafka consumer-lag monitoring
* [ ] RabbitMQ queue monitoring
* [ ] SLIs, SLOs, and alerting

### Delivery

* [x] GitOps with Argo CD — see [`docs/infrastructure/gitops.md`](docs/infrastructure/gitops.md)
* [ ] Automate image builds/publishing to a real registry so ArgoCD sync ships behavior
      changes, not just manifest changes
* [ ] Replace `infra/k8s/base/secrets.yaml`'s plaintext values with Sealed Secrets or
      External Secrets
* [ ] `staging`/`prod` overlays + a matching ArgoCD `Application` for each
* [ ] Image vulnerability scanning
* [ ] Canary / blue-green deployments

### Data

* [ ] Automated backups + restore testing
* [ ] Read replicas
* [ ] Elasticsearch indexing consumers

---

## Project Status

This project is a **production-oriented engineering platform and learning project**. It runs
real microservices, messaging, containers, Kubernetes, and observability infrastructure —
including a working transactional outbox, rate limiting, and a circuit breaker — but it should
not be described as production-certified.

Items in this file marked 🏗️ **Designed** or 📋 **Planned** are intentionally not claimed as
implemented. The distinction is deliberate: the project documents both the current
implementation (see [`README.md`](README.md)) and the engineering decisions still ahead of it.
