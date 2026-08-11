# ADR-001: Database-per-service, no distributed transactions

## Status
Accepted

## Context
Multiple microservices need related data (e.g. appointment-service and billing-service both
care about an appointment). A shared database would let one team's schema change silently
break another service.

## Decision
Every service owns its own PostgreSQL database (or schema, in the docker-compose learning
setup where they share one Postgres instance for convenience). No service ever queries
another service's tables directly, and no service holds credentials to another's database.
Cross-service consistency is achieved via domain events (Kafka) and the Saga pattern, never
via a two-phase-commit distributed transaction.

## Consequences
+ Services are independently deployable and can evolve their schemas freely.
+ Failure isolation: one database going down doesn't cascade.
- Eventual consistency is now a fact of life; the UI/API contracts must account for it
  (e.g. an appointment may briefly exist before its invoice does).
- Reporting across services requires either the analytics-service rollups or a dedicated
  read-model, not ad-hoc cross-database joins.
