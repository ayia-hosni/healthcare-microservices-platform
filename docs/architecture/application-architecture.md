# Application Architecture

> Part of [Architecture](README.md). Deep dive: [02-container-architecture.md](02-container-architecture.md).

The backend is ten independently deployable Spring Boot applications — nine domain services
plus the `graphql-gateway` BFF — fronted by an Angular SPA and a single NGINX ingress entry
point.

```text
Client Browser
      │
      ▼
Frontend (Angular SPA)
      │
      ▼
NGINX Ingress
      │
      ├── /api/v1/auth ──────────► identity-service
      ├── /api/v1/patients ──────► patient-service
      ├── /api/v1/doctors ───────► doctor-service
      ├── /api/v1/appointments ──► appointment-service
      └── /graphql, /graphiql ───► graphql-gateway ──► gRPC/REST fan-out to the above
```

Each service owns its business logic, persistence, configuration, API contracts, domain
events, and observability — see [Microservices](microservices.md) for how responsibilities
are split, and [02-container-architecture.md](02-container-architecture.md) for the full
container-level diagram including `emr-service`, `billing-service`,
`notification-service`, `audit-service`, and `analytics-service`.
