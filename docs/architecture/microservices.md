# Microservices

> Part of [Architecture](README.md). Deep dive: [03-service-architecture.md](03-service-architecture.md).

The platform is organized around business domains rather than technical layers.

```text
                              HEALTHCARE PLATFORM
                                      │
        ┌─────────────────────────────┼─────────────────────────────┐
        │                             │                             │
        ▼                             ▼                             ▼
 ┌───────────────┐             ┌───────────────┐             ┌───────────────┐
 │ Identity &    │             │ Clinical      │             │ Scheduling    │
 │ Security      │             │ Domain        │             │ Domain        │
 └───────┬───────┘             └───────┬───────┘             └───────┬───────┘
         │                             │                             │
         ▼                             ▼                             ▼
    Identity Service              Patient Service              Appointment Service
                                  Doctor Service
                                  EMR Service


        ┌─────────────────────────────┼─────────────────────────────┐
        │                             │                             │
        ▼                             ▼                             ▼
 ┌───────────────┐             ┌───────────────┐             ┌───────────────┐
 │ Financial     │             │ Supporting    │             │ API / Edge    │
 │ Domain        │             │ Services      │             │ Layer         │
 └───────┬───────┘             └───────┬───────┘             └───────┬───────┘
         │                             │                             │
         ▼                             ▼                             ▼
    Billing Service              Notification Service         NGINX Ingress
                                 Audit Service                GraphQL Gateway
                                 Analytics Service
```

The three Supporting Services share a Kafka-consumer pattern and, with it, a shared set of
at-least-once delivery caveats — see [Supporting Services](supporting-services.md).

---

## Services

Full per-service READMEs are linked from the [root README's Services table](../../README.md#-services).

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

Each service:

* owns a dedicated PostgreSQL database (see [Data Architecture](data-architecture.md))
* exposes its own REST API (and gRPC for patient/doctor lookups)
* publishes and/or consumes domain events over Kafka
* has its own Flyway migrations, tests, and Docker image

See [03-service-architecture.md](03-service-architecture.md) for the full per-service
responsibility breakdown, and [`../../PROGRESS.md`](../../PROGRESS.md) for what's implemented
versus still planned in each one.
