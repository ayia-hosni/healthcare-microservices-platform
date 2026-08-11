# ADR-006: Messaging hardening roadmap (Kafka + RabbitMQ → production-grade)

## Status
Proposed

## Context
[[0005-kafka-vs-rabbitmq-scenarios]] documents *why* the platform uses Kafka for events and
RabbitMQ for jobs. This ADR is the follow-up a senior architect would write next: what's
missing between "works in a learning build" and "safe to run with real PHI," ordered by risk,
not by interest.

Audit of the current state (not hypothetical — verified against this codebase):

| Gap | Evidence |
|---|---|
| No transport security on either broker | `docker-compose.yml`: Kafka `PLAINTEXT` listener, no TLS; Rabbit has no TLS config |
| RabbitMQ still on default `guest`/`guest` even in the k8s overlay | `infra/k8s/base/configmap.yaml` sets `RABBITMQ_HOST` but no `RABBITMQ_USER`/`RABBITMQ_PASSWORD` in configmap or secrets.yaml — services fall back to `guest:guest` per `application.yml` defaults |
| No schema governance | every consumer does `objectMapper.readTree(rawEvent)` / `StringDeserializer` — no schema registry, no compatibility check, a typo'd field breaks a consumer at runtime, not at build time |
| No producer delivery guarantees set | no `acks`, `enable.idempotence`, or `transactional-id` in any `application.yml` — relying entirely on client defaults |
| Kafka consumers have no DLQ, only log-and-drop | `AppointmentEventConsumer.onAppointmentEvent` catches, logs, and **swallows** the exception — the event is gone. RabbitMQ's DLQ (ADR-005 scenario 2) is not mirrored on the Kafka side |
| Outbox pattern not implemented | already tracked in [[0002-outbox-pattern]] — publish-after-commit can silently drop events on crash |
| No lag/queue-depth observability | Zipkin gives request tracing; nothing surfaces consumer lag or DLQ depth, so a stuck consumer group fails silently until someone notices missing invoices |
| Single-broker topology everywhere | one Kafka broker, no Rabbit clustering/quorum queues — fine for `docker-compose`, not for anything with an uptime expectation |

This is healthcare data. Patient identifiers and appointment/EMR details cross these brokers
in `payload` fields. Sections below are ordered so that **P0 must land before any environment
touches real PHI**, not just before "launch."

## Decision — phased roadmap

### P0 — Security & compliance (blocking for any real PHI)
1. **TLS everywhere.** Kafka: switch `docker-compose`/k8s listeners from `PLAINTEXT` to
   `SASL_SSL` (or at minimum `SSL` intra-cluster + `SASL_PLAINTEXT` behind a mesh with mTLS).
   RabbitMQ: enable the `amqps` listener, disable plain `5672` outside the cluster network.
2. **Real credentials, no `guest:guest`.** Generate per-environment Rabbit users with scoped
   vhost permissions; put `RABBITMQ_USER`/`RABBITMQ_PASSWORD` and Kafka SASL credentials into
   `infra/k8s/base/secrets.yaml` (already the pattern used for DB creds — extend it, don't
   invent a new one) rather than configmap or code defaults.
3. **Topic/queue-level ACLs.** Kafka ACLs so e.g. `analytics-service`'s consumer credential
   cannot produce to `appointment.events`; Rabbit permissions scoped per vhost/queue the same
   way. Least privilege per service identity, not one shared credential.
4. **Field-level thinking on PHI in payloads.** Decide per event type whether the payload
   needs the raw patient name/DOB or can carry a `patientId` the receiving service resolves
   itself (most events here already do this correctly — e.g. `AppointmentCreatedEvent` carries
   IDs, not names — but codify it as a rule so it doesn't regress as new events are added).

### P1 — Delivery guarantees (correctness under failure)
1. **Producer reliability config**, explicit not implicit, in every service's
   `application.yml`:
   ```yaml
   spring.kafka.producer.acks: all
   spring.kafka.producer.properties.enable.idempotence: true
   ```
2. **Close the outbox gap from ADR-002.** This is the single highest-leverage P1 item: without
   it, a crash between DB commit and Kafka publish silently drops the event, and nothing above
   fixes that.
3. **DLQ parity for Kafka consumers — done.** `billing-service`, `audit-service`,
   `analytics-service`, and `notification-service` each now have a `KafkaDlqConfig`
   (`config` package) wiring a `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` into
   `kafkaListenerContainerFactory`: 1 initial attempt + 2 retries (1s apart), then the record
   is published to `<topic>.DLT` via a dedicated String/String producer, independent of each
   service's own event-publishing serializer. This also required removing the
   catch-log-swallow in `AppointmentEventConsumer`, `DomainEventAuditConsumer`, and
   `DomainEventAnalyticsConsumer` — they previously caught and logged without rethrowing,
   which both hid the failure from the container's error handler *and* let the surrounding
   `@Transactional` commit whatever partial state existed, since Spring only rolls back on a
   propagated exception. `NotificationRequestConsumer` already rethrew, so it only needed the
   config, not a consumer-side change. Remaining: a documented replay runbook for the new
   `.DLT` topics (tracked under P3 below).
4. **Idempotent consumers everywhere, not just billing.** `AppointmentEventConsumer` already
   does this right (`findAllByAppointmentId` guard). Audit that the same guard exists in
   `audit-service` and `analytics-service` before they're relied on for compliance reporting —
   an audit log with duplicate entries is worse than one with gaps.

### P2 — Schema governance
1. Introduce a schema registry (Confluent/Apicurio) and move off raw `JsonSerializer`/
   `readTree` string parsing to Avro or Protobuf with `BACKWARD` compatibility mode.
2. Version events explicitly (`AppointmentCreatedEventV1`) so a producer can add a field
   without a coordinated multi-service deploy, and a consumer can reject an incompatible
   version instead of NPE-ing on a missing field.
3. Contract tests per topic (Pact or a schema-compatibility CI check) so a PR that breaks a
   downstream consumer fails in CI, not in `audit-service`'s logs three days later.

### P3 — Observability & operability
1. Consumer lag (Kafka) and queue depth + DLQ count (Rabbit) as first-class metrics, scraped
   by the existing Prometheus/Grafana stack already in `docker-compose.yml` — it's deployed but
   not yet pointed at broker metrics.
2. Alert on: consumer group lag above threshold, any message landing in `notification.dlq.queue`
   or a Kafka `.DLT` topic, RabbitMQ retry-queue depth trending up (a sign the downstream
   provider, e.g. SES, is failing).
3. A documented replay runbook: how to re-drive `notification.dlq.queue` after a fix, how to
   reset a Kafka consumer group's offset for a bounded replay. Undocumented today — the DLQ
   exists but nothing says what to do once something lands there.

### P4 — Topology & scale
1. RabbitMQ: move from classic queues to **quorum queues** for the notification queue family —
   classic mirrored queues are deprecated upstream and quorum queues give proper Raft-based HA.
2. Kafka: set explicit partition counts and a partition key (e.g. `patientId` or
   `appointmentId`) per topic so ordering is guaranteed per-entity and consumer parallelism is
   intentional, not left at the single-partition default.
3. Multi-broker Kafka (3 brokers, `replication.factor=3`, `min.insync.replicas=2`) and a
   3-node Rabbit cluster before this is load-bearing outside `docker-compose`.

## Consequences
+ Ordered by blast radius: P0 is a compliance/security gate, not a nice-to-have, given this
  is a healthcare platform; everything after it is graded reliability/ops maturity.
+ Reuses existing patterns instead of inventing new ones — the outbox gap was already tracked
  (ADR-002), the DLQ pattern to copy already exists (`RabbitMqConfig`), the secrets pattern to
  extend already exists (DB creds in `infra/k8s/base/secrets.yaml`).
- This is a lot of surface area across 9 services; it should be executed incrementally
  (P0 → P1 → …) rather than as one change, and P0 in particular should land before any
  environment carries real patient data, not bundled with feature work.

## Suggested next step
Pick one P0 item to implement first — TLS/SASL on Kafka, real RabbitMQ credentials wired
through `infra/k8s/base/secrets.yaml`, or the Kafka DLQ parity in P1 (arguably the fastest
win: it's copying a pattern that already exists in this codebase). Say which and I'll scope
the concrete file-level changes.
