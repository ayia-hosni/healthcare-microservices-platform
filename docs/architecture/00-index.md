# Architecture Documentation

This folder is the detailed architecture reference for the platform. The [top-level
README](../../README.md#architecture) covers the high-level shape and the day-to-day
developer workflow; these documents go one level deeper on each concern.

Every diagram here is drawn from the current code and the [ADRs](../adr/) — not from an
aspirational target. Where the platform has a real gap (a planned-but-not-built feature,
a simplification made for a learning build), the document says so explicitly rather than
presenting it as done. That mirrors the project's own [ADR-0001](../adr/0001-api-gateway-ingress.md)
and [ADR-0002](../adr/0002-messaging-topology.md), both of which exist because an earlier
diagram didn't match the code and someone had to reconcile them.

## Contents

| # | Document | Answers |
|---|----------|---------|
| 01 | [System Context](01-system-context.md) | Who and what talks to this platform from the outside? |
| 02 | [Container Architecture](02-container-architecture.md) | What are the deployable units and how do they fit together? |
| 03 | [Service Architecture](03-service-architecture.md) | What are the nine domain services, grouped by responsibility? |
| 04 | [Communication Architecture](04-communication-architecture.md) | When does the platform use REST/GraphQL, gRPC, or Kafka — and why? |
| 05 | [Event Topology](05-event-topology.md) | What events exist, who produces them, who consumes them? |
| 06 | [Data Architecture](06-data-architecture.md) | How is data owned and stored across services? |
| 07 | [Security Architecture](07-security-architecture.md) | How does auth work, and what are the trust boundaries? |
| 08 | [Deployment Architecture](08-deployment-architecture.md) | How does this run on Kubernetes? |
| 09 | [Observability Architecture](09-observability-architecture.md) | How do you tell what the system is doing? |
| 10 | [Appointment Booking — Sequence](10-appointment-booking-sequence.md) | What actually happens, step by step, on the platform's core workflow? |

## Related

* [Architecture Decision Records](../adr/) — the *why* behind each of these diagrams.
* [Distributed Systems & Reliability](../../README.md#distributed-systems--reliability) — what's implemented vs. planned.
* [Path to Production](../../README.md#path-to-production) — the gap between this repo and a production-certified deployment.
