# Healthcare Microservices Platform

A **cloud-native, production-shaped healthcare platform** built to demonstrate the architecture and engineering practices required to design, operate, and scale distributed backend systems.

The platform consists of **9 Spring Boot microservices** built with **Java 21**, an event-driven architecture powered by **Apache Kafka and RabbitMQ**, database-per-service isolation with PostgreSQL, Redis caching, Elasticsearch, object storage, containerization, Kubernetes, Helm, and a full observability stack.

> **The goal of this project is not to build a simple CRUD healthcare application.**
>
> It is designed as a hands-on demonstration of **distributed systems, scalability, reliability, security, observability, asynchronous processing, and cloud-native infrastructure**.

---

## Architecture

```text
                                  ┌─────────────────┐
                                  │     Frontend    │
                                  └────────┬────────┘
                                           │
                                           ▼
                                  ┌─────────────────┐
                                  │   API Gateway   │
                                  └────────┬────────┘
                                           │
                 ┌─────────────────────────┼─────────────────────────┐
                 │                         │                         │
                 ▼                         ▼                         ▼
        ┌─────────────────┐      ┌─────────────────┐      ┌─────────────────┐
        │ Identity Service│      │ Patient Service │      │ Doctor Service  │
        └─────────────────┘      └─────────────────┘      └─────────────────┘
                 │                         │                         │
                 └─────────────────────────┼─────────────────────────┘
                                           │
                                           ▼
                                  ┌─────────────────┐
                                  │     Kafka       │
                                  │ Event Streaming │
                                  └────────┬────────┘
                                           │
              ┌────────────────────────────┼───────────────────────────┐
              │                            │                           │
              ▼                            ▼                           ▼
     ┌─────────────────┐         ┌─────────────────┐         ┌─────────────────┐
     │ Audit Service   │         │ Analytics       │         │ Notification    │
     │                 │         │ Service         │         │ Service         │
     └─────────────────┘         └─────────────────┘         └────────┬────────┘
                                                                       │
                                                                       ▼
                                                               ┌───────────────┐
                                                               │   RabbitMQ    │
                                                               │ Retry + DLQ   │
                                                               └───────────────┘

       ┌──────────────────────────────────────────────────────────────────────┐
       │                         Business Services                             │
       │                                                                      │
       │ Appointment │ EMR │ Billing │ Patient │ Doctor │ Identity           │
       └──────────────────────────────────────────────────────────────────────┘

       ┌──────────────────────────────────────────────────────────────────────┐
       │                           Data Layer                                  │
       │                                                                      │
       │ PostgreSQL │ Redis │ Elasticsearch │ MinIO                           │
       └──────────────────────────────────────────────────────────────────────┘

       ┌──────────────────────────────────────────────────────────────────────┐
       │                         Observability                                 │
       │                                                                      │
       │ Spring Actuator │ Prometheus │ Grafana │ Zipkin                      │
       └──────────────────────────────────────────────────────────────────────┘

       Docker Compose → Kubernetes → Kustomize → Helm → AWS/EKS
```

---

# Services

| Service                | Port | Responsibility                                                       |
| ---------------------- | ---: | -------------------------------------------------------------------- |
| `identity-service`     | 8081 | Registration, authentication, JWT issuance, refresh, logout and RBAC |
| `patient-service`      | 8082 | Patient demographics, insurance and medical history                  |
| `doctor-service`       | 8083 | Doctor profiles, departments, specialties and availability           |
| `appointment-service`  | 8084 | Booking, cancellation, rescheduling and waiting lists                |
| `emr-service`          | 8085 | Encounters, diagnoses, medications, laboratory results and allergies |
| `billing-service`      | 8086 | Invoices, payments and insurance claims                              |
| `notification-service` | 8087 | Asynchronous email/SMS/push processing using RabbitMQ                |
| `audit-service`        | 8088 | Platform-wide append-only domain-event auditing                      |
| `analytics-service`    | 8089 | Event-driven analytics and scheduled reporting                       |

A shared `common` module contains reusable infrastructure such as domain events, DTOs, exceptions and JWT verification. It is a library module rather than an independently deployed service.

---

# Engineering Highlights

## 1. Microservices Architecture

Each business domain is independently deployable and owns its persistence boundary.

```text
Patient Service      → patient_db
Doctor Service       → doctor_db
Appointment Service  → appointment_db
Billing Service      → billing_db
EMR Service          → emr_db
...
```

This reduces coupling between domains and allows services to evolve, deploy and scale independently.

---

## 2. Event-Driven Architecture

Business state changes are represented as domain events and published through Kafka.

For example:

```text
Appointment Service
        │
        │ AppointmentCreated
        ▼
      Kafka
        │
        ├──────────────► Billing Service
        │
        ├──────────────► Notification Service
        │
        ├──────────────► Audit Service
        │
        └──────────────► Analytics Service
```

Consumers remain decoupled from the service that produced the event.

This architecture also provides a foundation for asynchronous processing and independent horizontal scaling of consumers.

---

## 3. Choreographed Saga

Appointment creation demonstrates a choreographed distributed workflow.

```text
Appointment Created
        │
        ▼
      Kafka
     /      \
    ▼        ▼
Billing    Notification
```

Services react to domain events instead of directly calling each other for every workflow step.

The architectural decision is documented in:

`docs/adr/0003-choreographed-saga.md`

---

## 4. Concurrency & Idempotency

Appointment booking is designed around the possibility of concurrent requests.

The booking flow uses:

* Client-provided idempotency keys
* Pessimistic locking for conflict detection
* Database unique constraints as the final consistency boundary
* Optimistic locking with `@Version` for lower-contention entities

This protects the system from duplicate requests and concurrent double-booking.

---

## 5. Authentication & Authorization

`identity-service` is responsible for authentication and token issuance.

Business services independently verify JWTs using the shared verification component.

```text
Client
  │
  ▼
Identity Service
  │
  │ Access Token
  ▼
Business Services
  │
  └── JWT Verification
```

The architecture intentionally separates **token issuance** from **token verification**.

Current implementation uses a shared development secret. A production-oriented RS256/JWKS model is documented as a future extension.

See:

`docs/adr/0004-shared-jwt-verification.md`

---

## 6. Reliable Asynchronous Processing

Notification processing uses RabbitMQ with:

* authenticated connections
* asynchronous consumers
* retry handling
* dead-letter queues

This separates user-facing business operations from potentially slow external notification providers.

```text
Application Event
       │
       ▼
    RabbitMQ
       │
       ▼
 Notification Worker
       │
       ├── Success
       │
       └── Failure → Retry → DLQ
```

---

## 7. Multiple Scheduling Strategies

The platform intentionally uses two different scheduling mechanisms.

### Spring `@Scheduled`

Used for lightweight stateless maintenance tasks such as token cleanup.

### Quartz

Used for analytics reporting with a JDBC-backed job store.

The distinction demonstrates when a simple scheduler is sufficient and when durable, persistent job execution is more appropriate.

---

# Data Architecture

The platform uses multiple storage technologies based on workload characteristics.

| Technology    | Purpose                        |
| ------------- | ------------------------------ |
| PostgreSQL    | Transactional service data     |
| Redis         | Caching and fast-access state  |
| Kafka         | Event streaming                |
| RabbitMQ      | Asynchronous work queues       |
| Elasticsearch | Search/indexing infrastructure |
| MinIO         | Object/file storage            |

The system intentionally avoids forcing every workload into a single database or messaging technology.

---

# Observability

Observability is treated as part of the platform rather than an afterthought.

### Metrics

**Prometheus**

Collects application and infrastructure metrics exposed through Spring Boot Actuator.

### Visualization

**Grafana**

Provides dashboards for monitoring application health and system behavior.

### Distributed Tracing

**Zipkin**

Provides request tracing across services and asynchronous workflows.

### Health

Every Spring Boot service exposes Actuator health information and container/Kubernetes health checks.

The observability stack is designed to answer questions such as:

* Which service is producing errors?
* Where is request latency increasing?
* Are consumers processing events?
* Which dependencies are unavailable?
* Is a service approaching resource limits?

---

# Containerization

The complete platform can be launched locally using Docker Compose.

Infrastructure includes:

* PostgreSQL
* Redis
* Kafka
* Zookeeper
* RabbitMQ
* Elasticsearch
* MinIO
* Prometheus
* Grafana
* Zipkin

alongside all 9 Spring Boot services.

Application containers use resource limits and health checks to approximate production deployment behavior.

---

# Kubernetes

The platform includes Kubernetes manifests organized using Kustomize.

```text
infra/k8s/
├── base/
└── overlays/
    └── dev/
```

The base configuration contains the application workloads while the development overlay provides a self-contained local environment including the required datastores and messaging infrastructure.

The project also includes a Minikube deployment script:

```bash
./infra/k8s/scripts/deploy-to-minikube.sh
```

Kubernetes configuration demonstrates:

* Deployments
* Services
* ConfigMaps
* Secrets
* Health probes
* Resource requests and limits
* Environment-specific overlays
* Container image configuration

See:

`infra/k8s/README.md`

---

# Helm

The project includes an umbrella Helm chart:

```text
infra/helm/healthcare-platform/
├── Chart.yaml
├── values.yaml
└── templates/
```

The chart provides a centralized way to configure and deploy the backend services while allowing service-specific configuration through `values.yaml`.

---

# Local Development

## Prerequisites

Install:

* Docker
* Docker Compose
* Java 21
* Maven
* Git

For Kubernetes development:

* Minikube
* kubectl
* Helm

---

## Start with Docker Compose

Generate the development Kafka certificates:

```bash
./infra/docker/kafka/generate-dev-certs.sh
```

Then start the platform:

```bash
docker compose up --build
```

To run in the background:

```bash
docker compose up --build -d
```

Check running containers:

```bash
docker compose ps
```

View logs:

```bash
docker compose logs -f
```

View logs for a specific service:

```bash
docker compose logs -f appointment-service
```

Stop the platform:

```bash
docker compose down
```

---

# Development Endpoints

| Component            | URL                      |
| -------------------- | ------------------------ |
| Identity Service     | `http://localhost:8081`  |
| Patient Service      | `http://localhost:8082`  |
| Doctor Service       | `http://localhost:8083`  |
| Appointment Service  | `http://localhost:8084`  |
| EMR Service          | `http://localhost:8085`  |
| Billing Service      | `http://localhost:8086`  |
| Notification Service | `http://localhost:8087`  |
| Audit Service        | `http://localhost:8088`  |
| Analytics Service    | `http://localhost:8089`  |
| RabbitMQ Management  | `http://localhost:15672` |
| Elasticsearch        | `http://localhost:9200`  |
| MinIO Console        | `http://localhost:9001`  |
| Prometheus           | `http://localhost:9090`  |
| Grafana              | `http://localhost:3000`  |
| Zipkin               | `http://localhost:9411`  |

Swagger UI is available on each application service through its configured Swagger endpoint.

---

# Example Workflow

Register a patient:

```bash
curl -X POST http://localhost:8081/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "aya@example.com",
    "password": "supersecurepassword123"
  }'
```

The response contains an access token.

Use the token to access another service:

```bash
curl \
  "http://localhost:8083/api/v1/doctors/search?specialty=Cardiology" \
  -H "Authorization: Bearer <accessToken>"
```

A more complete workflow can then span:

```text
Authentication
      ↓
Doctor Search
      ↓
Appointment Booking
      ↓
AppointmentCreated Event
      ↓
 ┌────┴─────────────┐
 ▼                  ▼
Billing         Notification
 │                  │
 ▼                  ▼
Invoice          RabbitMQ
                   │
                   ▼
              Notification
```

---

# CI/CD

The repository includes GitHub Actions for the application lifecycle.

```text
Commit
  │
  ▼
Compile
  │
  ▼
Unit Tests
  │
  ▼
Integration Tests
  │
  ▼
Static Analysis
  │
  ▼
Docker Build
  │
  ▼
Container Registry
  │
  ▼
Deployment
```

Integration tests use Testcontainers to execute tests against real infrastructure dependencies rather than relying exclusively on mocks.

The workflow is located at:

```text
.github/workflows/ci.yml
```

---

# Infrastructure as Code & Deployment Paths

The project intentionally supports multiple deployment environments.

```text
                     ┌──────────────────┐
                     │ Docker Compose   │
                     │ Local Development│
                     └────────┬─────────┘
                              │
                              ▼
                     ┌──────────────────┐
                     │     Minikube     │
                     │ Kubernetes Dev   │
                     └────────┬─────────┘
                              │
                              ▼
                     ┌──────────────────┐
                     │ Kubernetes/Helm  │
                     │ Cloud Deployment │
                     └──────────────────┘
```

This separation allows the same application architecture to be exercised from local development through container orchestration and cloud deployment.

---

# Architecture Decision Records

Important architectural decisions are documented under:

```text
docs/adr/
```

Examples include:

* Microservices architecture
* Database-per-service
* Outbox pattern
* Choreographed saga
* Shared JWT verification
* Messaging security
* Kubernetes deployment
* Observability strategy

Each ADR documents the context, alternatives, decision and consequences rather than simply documenting what the code currently does.

---

# Reliability & Distributed Systems Considerations

The project deliberately explores several problems that appear in distributed systems:

### Current implementations

* Service isolation
* Asynchronous messaging
* Event-driven communication
* Idempotent appointment requests
* Database constraints
* Optimistic locking
* Pessimistic locking
* Retry/DLQ processing
* Health checks
* Container restart policies
* Resource limits
* Persistent Quartz jobs
* Authentication and authorization
* Metrics and distributed tracing

### Planned extensions

The following are intentionally documented rather than falsely presented as complete:

* Transactional Outbox
* RS256/JWKS authentication
* Orchestrated saga compensation
* Circuit breakers and bulkheads
* Elasticsearch indexing consumers
* Advanced autoscaling
* Production secret management
* Advanced Kafka observability and consumer-lag monitoring

---

# Production Hardening Roadmap

The current repository is **production-shaped, not production-certified**.

The next engineering steps toward a real production deployment would include:

1. Replace development secrets with a dedicated secret-management solution.
2. Move JWT signing from shared HMAC secrets to RS256/JWKS.
3. Implement the transactional Outbox pattern.
4. Add comprehensive circuit-breaker and bulkhead policies.
5. Add Kafka consumer-lag monitoring.
6. Introduce Kubernetes HPA/VPA where appropriate.
7. Add centralized logging and log correlation.
8. Implement stronger network policies and service-to-service security.
9. Add load testing and capacity benchmarks.
10. Establish measurable SLIs/SLOs and alerting policies.
11. Add production-grade database backup and recovery procedures.
12. Introduce progressive deployment strategies such as rolling or canary releases.

---

# Project Structure

```text
.
├── backend/
│   ├── common/
│   ├── identity-service/
│   ├── patient-service/
│   ├── doctor-service/
│   ├── appointment-service/
│   ├── emr-service/
│   ├── billing-service/
│   ├── notification-service/
│   ├── audit-service/
│   └── analytics-service/
│
├── frontend/
│
├── infra/
│   ├── docker/
│   │   ├── kafka/
│   │   ├── init-multiple-dbs.sh
│   │   └── prometheus.yml
│   │
│   ├── k8s/
│   │   ├── base/
│   │   ├── overlays/
│   │   └── scripts/
│   │
│   └── helm/
│       └── healthcare-platform/
│
├── docs/
│   ├── adr/
│   ├── architecture/
│   ├── operations/
│   └── runbooks/
│
├── .github/
│   └── workflows/
│
├── docker-compose.yml
└── README.md
```

---
