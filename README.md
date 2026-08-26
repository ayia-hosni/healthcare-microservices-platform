# 🏥 Healthcare Platform

> A cloud-native healthcare platform built with **Java 21, Spring Boot, Angular, Kubernetes, Kafka-compatible event streaming, RabbitMQ, PostgreSQL, Redis, Terraform, and cloud-native observability**.

## Overview

This project is a portfolio-scale implementation of a distributed healthcare platform designed to explore modern backend and cloud-native engineering practices.

The platform is built around independently deployable services, asynchronous communication, database-per-service boundaries, container orchestration, infrastructure as code, and observable operations.

It is designed to demonstrate practical experience with:

* Java and Spring Boot microservices
* distributed system design
* event-driven architecture
* REST, GraphQL, and gRPC communication
* PostgreSQL and database-per-service architecture
* Redis caching
* Kafka-compatible event streaming
* RabbitMQ work queues and retry handling
* Docker and Kubernetes
* Azure and AWS cloud architecture
* Terraform infrastructure as code
* GitOps and Argo CD
* Prometheus, Grafana, logging, and distributed tracing

## For Recruiters & Hiring Managers

### Engineering Focus

| Area            | Technologies                             |
| --------------- | ---------------------------------------- |
| Backend         | Java 21, Spring Boot                     |
| Frontend        | Angular                                  |
| Architecture    | Microservices, Event-Driven Architecture |
| APIs            | REST, GraphQL, gRPC                      |
| Database        | PostgreSQL                               |
| Cache           | Redis                                    |
| Event Streaming | Kafka / Azure Event Hubs                 |
| Messaging       | RabbitMQ                                 |
| Containers      | Docker                                   |
| Orchestration   | Kubernetes                               |
| Cloud           | Azure, AWS                               |
| Infrastructure  | Terraform                                |
| GitOps          | Argo CD                                  |
| Metrics         | Prometheus                               |
| Dashboards      | Grafana                                  |
| Tracing         | Zipkin                                   |
| Search          | Elasticsearch                            |

### What This Project Demonstrates

* Designing services around business domains.
* Applying database-per-service architecture.
* Using events for asynchronous service communication.
* Running containerized workloads on Kubernetes.
* Managing infrastructure with Terraform.
* Separating work queues from event streaming.
* Implementing cloud-native identity and secret management.
* Designing observable distributed systems.
* Supporting local development and cloud deployment paths.

## Architecture at a Glance

```text
Client
   │
   ▼
Frontend
   │
   ▼
API Gateway / GraphQL
   │
   ▼
Kubernetes
   │
   ├── Identity Service
   ├── Patient Service
   ├── Doctor Service
   ├── Appointment Service
   ├── EMR Service
   ├── Billing Service
   ├── Notification Service
   ├── Audit Service
   └── Analytics Service
          │
          ├── PostgreSQL
          ├── Redis
          ├── Event Streaming
          ├── RabbitMQ
          ├── Elasticsearch
          └── Object Storage
```

## Documentation

Start with the documentation index:

➡️ **[Documentation Index](INDEX.md)**

### Recommended Paths

#### 👔 Recruiters & Hiring Managers

1. [Project Overview](README.md)
2. [Architecture Overview](docs/architecture/README.md)
3. [Technology Stack](docs/architecture/README.md#technology-stack)
4. [Cloud Architecture](docs/cloud/README.md)
5. [Project Progress](PROGRESS.md)

#### 👨‍💻 Backend Developers

1. [Application Architecture](docs/architecture/application-architecture.md)
2. [Microservices](docs/architecture/microservices.md)
3. [Service Communication](docs/architecture/communication.md)
4. [Data Architecture](docs/architecture/data-architecture.md)
5. [Local Development](docs/development/local-development.md)

#### ☁️ DevOps / Cloud Engineers

1. [Cloud Architecture](docs/cloud/README.md)
2. [Kubernetes](docs/infrastructure/kubernetes.md)
3. [Terraform](docs/infrastructure/terraform.md)
4. [GitOps](docs/infrastructure/gitops.md)
5. [Observability](docs/operations/observability.md)

## Quick Start

See:

➡️ [Local Development Guide](docs/development/local-development.md)

## Cloud Architecture

The project includes cloud architecture documentation for:

* [Azure](docs/cloud/azure/README.md)
* [AWS](docs/cloud/aws/README.md)

## Project Status

See:

➡️ [PROGRESS.md](PROGRESS.md)

---

For the complete documentation map, start at ➡️ [INDEX.md](INDEX.md).
