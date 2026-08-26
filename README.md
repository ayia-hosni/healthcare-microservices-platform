# 🏥 Healthcare Microservices Platform

> A cloud-native, event-driven healthcare platform built with Java and Spring Boot to explore microservices architecture, distributed systems, reliability patterns, API design, and Kubernetes operations.

![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-6DB33F?logo=springboot)
![Angular](https://img.shields.io/badge/Angular-Frontend-DD0031?logo=angular)
![Kafka](https://img.shields.io/badge/Apache%20Kafka-Event--Driven-black?logo=apachekafka)
![Kubernetes](https://img.shields.io/badge/Kubernetes-Cloud--Native-326CE5?logo=kubernetes)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-Database-4169E1?logo=postgresql)
![Docker](https://img.shields.io/badge/Docker-Containerized-2496ED?logo=docker)

---

## 📌 Overview

This project is a distributed healthcare platform composed of **nine independently deployable Spring Boot domain services** plus a **GraphQL BFF** (`graphql-gateway`) — ten Spring Boot applications in total, all built on **Java 21 / Spring Boot 3.3**, fronted by an **Angular SPA**.

The architecture explores how a production-oriented system can handle:

- Domain-oriented microservices
- Service-to-service communication (REST, gRPC, GraphQL)
- Synchronous and asynchronous workflows
- Event-driven architecture with a transactional outbox
- Database-per-service boundaries
- Distributed concurrency and idempotency
- Reliable messaging (retry / DLQ)
- Rate limiting and circuit breaking on cross-boundary calls
- Containerization and Kubernetes deployment
- Observability (metrics, tracing, health)

---

## 📚 Documentation

This README is the front door. Everything else lives in a topic-focused sibling doc:

| Doc | Covers |
| --- | --- |
| [`GETTING_STARTED.md`](GETTING_STARTED.md) | Three ways to run it locally, easy → hard: Docker Compose, Minikube, or a hybrid dev loop |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | System design — services, communication model, the appointment booking workflow, database-per-service, event-driven architecture |
| [`RELIABILITY.md`](RELIABILITY.md) | Transactional outbox, rate limiting & circuit breakers, notification retry/DLQ, auth, storage tech choices, scheduling |
| [`SUPPORTING_SERVICES.md`](SUPPORTING_SERVICES.md) | notification/audit/analytics-service's shared Kafka-consumer pattern, and where each one's at-least-once delivery shows up as a real gap |
| [`OPERATIONS.md`](OPERATIONS.md) | Observability, Docker Compose, Kubernetes/Kustomize, Helm, endpoint reference, CI/CD |
| [`GITOPS.md`](GITOPS.md) | ArgoCD-driven deployment — how the reconcile loop works, and its known limitations |
| [`PROGRESS.md`](PROGRESS.md) | What's implemented vs. designed vs. planned, maturity breakdown, production roadmap |
| [`AZURE_ARCHITECTURE.md`](AZURE_ARCHITECTURE.md) | The platform's real, provisioned Azure cloud deployment |
| [`AWS_ARCHITECTURE.md`](AWS_ARCHITECTURE.md) | A designed-but-not-built AWS equivalent |
| [`docs/adr/`](docs/adr/) | Architecture Decision Records |
| [`docs/architecture/`](docs/architecture/00-index.md) | Deep-dive C4-style diagrams per concern |

---

## 🔌 Services

| Service                | Port | Responsibility                                                        |
| ----------------------- | ---: | --------------------------------------------------------------------- |
| `identity-service`     | 8081 | Registration, authentication, JWT issuance, refresh, logout, and RBAC |
| `patient-service`      | 8082 | Patient demographics, insurance, and medical history                  |
| `doctor-service`       | 8083 | Doctor profiles, departments, specialties, and availability           |
| `appointment-service`  | 8084 | Appointment booking, cancellation, rescheduling, and waiting lists    |
| `emr-service`          | 8085 | Encounters, diagnoses, medications, laboratory results, and allergies |
| `billing-service`      | 8086 | Invoices, payments, and payer eligibility checks                      |
| `notification-service` | 8087 | Asynchronous email, SMS, and push notification processing             |
| `audit-service`        | 8088 | Platform-wide append-only domain event auditing                       |
| `analytics-service`    | 8089 | Event-driven analytics and scheduled reporting                        |
| `graphql-gateway`      | 8090 | GraphQL BFF aggregating patient/doctor gRPC lookups and appointment/billing REST APIs behind one schema |

---

## 🚀 Quickstart

```bash
./infra/docker/kafka/generate-dev-certs.sh
docker compose up --build -d
docker compose ps
```

That's the fastest path. For two other ways to run it locally — Kubernetes via Minikube, or a
hybrid dev loop for hacking on one service with hot reload — see
[`GETTING_STARTED.md`](GETTING_STARTED.md). For the full endpoint list, Helm chart, and CI/CD,
see [`OPERATIONS.md`](OPERATIONS.md).

---

## 📁 Project Structure

```text
.
├── backend/
│   ├── grpc-contracts/
│   ├── common/
│   ├── identity-service/
│   ├── patient-service/
│   ├── doctor-service/
│   ├── appointment-service/
│   ├── emr-service/
│   ├── billing-service/
│   ├── notification-service/
│   ├── audit-service/
│   ├── analytics-service/
│   └── graphql-gateway/
│
├── frontend/                    # Angular SPA
│
├── infra/
│   ├── docker/
│   ├── k8s/
│   ├── helm/
│   ├── terraform/
│   ├── native/                  # run.sh / stop.sh — no Docker, no Kubernetes
│   └── argocd/                  # AppProject + Application — see GITOPS.md
│
├── docs/
│   ├── adr/
│   └── architecture/
│
├── .github/workflows/
├── docker-compose.yml
├── README.md
├── GETTING_STARTED.md
├── ARCHITECTURE.md
├── RELIABILITY.md
├── OPERATIONS.md
├── GITOPS.md
├── SUPPORTING_SERVICES.md
├── PROGRESS.md
├── AZURE_ARCHITECTURE.md
└── AWS_ARCHITECTURE.md
```

---

> **A practical cloud-native healthcare platform exploring how independently deployable services communicate, own data, handle concurrency, process events reliably via an outbox, guard against overload with rate limiting and circuit breakers, and evolve toward production-grade distributed systems architecture.**
