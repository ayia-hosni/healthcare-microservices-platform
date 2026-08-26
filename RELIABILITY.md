# 🛡️ Reliability & Security

How the platform behaves under failure and load: the transactional outbox, rate limiting and
circuit breaking, notification retry/DLQ handling, authentication, storage technology choices,
and job scheduling. For service boundaries and communication, see
[`ARCHITECTURE.md`](ARCHITECTURE.md). For how to run and deploy it, see
[`OPERATIONS.md`](OPERATIONS.md). For implementation status, see [`PROGRESS.md`](PROGRESS.md).

---

## Table of Contents

* [Transactional Outbox Pattern](#-transactional-outbox-pattern)
* [Reliability: Rate Limiting & Circuit Breakers](#-reliability-rate-limiting--circuit-breakers)
* [Reliable Notification Processing](#-reliable-notification-processing)
* [Authentication & Authorization](#-authentication--authorization)
* [Data & Storage Architecture](#-data--storage-architecture)
* [Scheduling](#-scheduling)
* [Core Architecture Principles](#-core-architecture-principles)

---

## 📨 Transactional Outbox Pattern

**Status: ✅ Implemented** — this is real, running code, not a future design.

`common`'s `OutboxEvent` / `OutboxWriter` / `OutboxEventRepository` / `OutboxRelay` classes
back a shared outbox table, with Flyway migrations in `patient-service`, `doctor-service`,
`appointment-service`, `billing-service`, and `emr-service`.

```text
Business Request
       │
       ▼
┌─────────────────────────────┐
│     Database Transaction    │
│                             │
│  1. Save Business Entity    │
│  2. Save Outbox Row         │
└──────────────┬──────────────┘
               │  (single atomic commit —
               │   no dual-write problem)
               ▼
        OutboxRelay (polls table)
               │
               ▼
             Kafka
```

The business state and the event record are written atomically in the same transaction, so
publishing a domain event can never silently succeed or fail independently of the database
write that triggered it.

---

## 🛡️ Reliability: Rate Limiting & Circuit Breakers

**Status: ✅ Implemented** (per-instance, via resilience4j) — not yet distributed/global.

```text
Client
   │
   ▼
POST /api/v1/appointments
   │
   ▼
@RateLimiter("bookingApi")  ── over limit ──► 429 (fallback: bookRejected)
   │ within limit
   ▼
Booking proceeds


billing-service
   │
   ▼
EligibilityService
   │
   ▼
@CircuitBreaker("payerEligibility")  ── payer SOAP endpoint failing ──► fallback: eligibilityUnavailable
   │ closed
   ▼
PayerEligibilityClient ──► External payer eligibility service (SOAP, see ADR-0003)
```

Both are declarative `resilience4j-spring-boot3` annotations (`@RateLimiter`, `@CircuitBreaker`)
with fallback methods — not manual try/catch. Bulkhead isolation and cross-instance/distributed
rate limiting are not implemented yet (see [`PROGRESS.md`](PROGRESS.md#production-roadmap)).

---

## 📬 Reliable Notification Processing

The Notification Service uses RabbitMQ to isolate notification delivery from user-facing
business operations.

```text
Kafka: notification.requests
        │
        ▼
Notification Service
        │
        ▼
    RabbitMQ
        │
        ├──────────────► Success ──► Delivered (email / SMS / push)
        │
        └──────────────► Failure ──► Retry ──► Dead-Letter Queue
```

This prevents slow or failing notification providers from directly blocking core business
workflows.

---

## 🔐 Authentication & Authorization

The Identity Service is responsible for authentication and token issuance. Business services
independently verify JWTs using the shared verification component.

```text
Client
   │
   ▼
Identity Service ──Access Token──► Business Services ──► JWT Verification (common's JwtVerifier)
```

The architecture intentionally separates **token issuance** from **token verification**. The
current implementation uses a shared HS256 development secret (`Keys.hmacShaKeyFor`). The
designed RS256/JWKS evolution and the wider security roadmap are tracked in
[`PROGRESS.md`](PROGRESS.md#designed-not-yet-built).

---

## 🧠 Data & Storage Architecture

The platform uses different storage technologies based on workload requirements.

| Technology    | Purpose                            | Status |
| ------------- | ----------------------------------- | ------ |
| PostgreSQL    | Transactional service data         | ✅ Implemented |
| Redis         | Caching (`patient-service`, `doctor-service` `@Cacheable` reads) | ✅ Implemented |
| Kafka         | Event streaming                    | ✅ Implemented |
| RabbitMQ      | Asynchronous work queues (notification-service only) | ✅ Implemented |
| MinIO         | Object storage (`emr-service` clinical documents) | ✅ Implemented |
| Elasticsearch | Search and indexing infrastructure | 🏗️ Provisioned, no consumers wired yet |

The architecture intentionally avoids forcing every workload into a single database or
messaging technology.

---

## ⏰ Scheduling

The platform intentionally uses two scheduling strategies.

```text
Simple / Stateless Task ──► Spring @Scheduled   (e.g. AppointmentReminderJob)

Persistent / Durable Job ──► Quartz              (analytics reporting)
```

This demonstrates the difference between simple in-process scheduling and persistent,
durable job scheduling.

---

## 🧭 Core Architecture Principles

* Domain ownership and clear service boundaries
* Independent deployability
* Database ownership per service
* Explicit communication contracts
* Synchronous communication only when an immediate answer is required
* Asynchronous events for independent workflows
* Idempotency and a transactional outbox for distributed reliability
* Observability as a system capability, not an afterthought
* Security boundaries between public and internal components
* Honest distinction between implemented and planned capabilities — see [`PROGRESS.md`](PROGRESS.md)
