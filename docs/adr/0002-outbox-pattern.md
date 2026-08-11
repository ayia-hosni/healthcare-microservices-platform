# ADR-002: Outbox pattern for reliable event publishing (target state)

## Status
Accepted (not yet implemented in this learning build — see note below)

## Context
Writing to the database and publishing a Kafka event are two separate operations. If the
process crashes between them, the DB commit succeeds but the event never ships — silently
breaking every downstream consumer (billing, notification, audit, analytics).

## Decision
The target design is the transactional outbox pattern: write the domain event to an
`outbox` table in the SAME transaction as the business write, then have a separate poller
(or Debezium CDC connector) relay unshipped rows to Kafka and mark them sent.

## Current state in this codebase
For learning clarity, the services in this repository publish to Kafka directly after the
transactional commit (see e.g. `PatientEventPublisher`, `AppointmentEventPublisher`). This
is called out explicitly in code comments at every publish call site. Swapping in the outbox
table + poller is a contained change: add an `outbox` table via Flyway, write to it inside
the existing `@Transactional` service methods instead of calling KafkaTemplate directly, and
add a `@Scheduled` (or Debezium) relay job per service.

## Consequences
+ Simpler code today, good for learning the domain-event shape and consumer side first.
- A real production deployment of this platform should not ship without closing this gap.
