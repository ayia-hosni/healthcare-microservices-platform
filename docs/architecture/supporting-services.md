# 📡 Supporting Services

> Part of [Architecture](README.md).

`notification-service`, `audit-service`, and `analytics-service` form the platform's
**Supporting Services** domain (see [Microservices](microservices.md))
— none of them drive a primary workflow; all three react to what the rest of the platform
already did. That shared shape means they also share a shared set of failure modes, which is
what this doc is really about: the Kafka consumer/DLQ pattern all three use, where each one
diverges from it, and the concrete gaps between what each one's code comments claim and what
the code actually does.

Each service still has its own `README.md`
([`notification-service`](../../backend/notification-service/README.md) ·
[`audit-service`](../../backend/audit-service/README.md) ·
[`analytics-service`](../../backend/analytics-service/README.md)) for its own endpoints and
package layout — this doc is the cross-service view.

---

## Table of Contents

* [Why These Three Are Grouped](#why-these-three-are-grouped)
* [The Shared Pattern](#the-shared-pattern)
* [notification-service](#notification-service)
* [audit-service](#audit-service)
* [analytics-service](#analytics-service)
* [At-Least-Once, Compared](#at-least-once-compared)

---

## Why These Three Are Grouped

Every other service either owns a REST-facing domain (identity, patient, doctor, appointment,
emr, billing) or aggregates them (`graphql-gateway`). These three don't — they subscribe to
domain events the other services already published and do something with them after the fact:
audit-service records them, analytics-service counts them, notification-service delivers on
them. None of them is in the synchronous path of a client request; all three can be down
without a booking, a patient lookup, or an invoice failing.

## The Shared Pattern

All three consume from Kafka topics owned by other services, and all three use the *exact
same* Kafka-side error-handling configuration — not a similar one, a byte-for-byte identical
`KafkaDlqConfig` class in each service:

```text
Kafka record delivered to the consumer
              │
              ▼
     Listener method throws
              │
              ▼
   DefaultErrorHandler + FixedBackOff(1000L, 2)
   ── 1 initial attempt + 2 retries, 1 second apart ──
              │
        still failing?
              │
              ▼
   DeadLetterPublishingRecoverer
   ──► <original-topic>.DLT
```

None of the three has a Kafka *producer* for domain events of their own — the `KafkaTemplate`
each `KafkaDlqConfig` wires up exists solely to publish to that `.DLT` topic on failure, not to
participate in the platform's normal event flow.

```text
Kafka
 patient.events ─┐
 doctor.events   ├──► DomainEventAuditConsumer ──────► audit_db          (audit-service)
 appointment.    │    groupId: audit-service
 events          │
 emr.events      ├──► DomainEventAnalyticsConsumer ──► analytics_db      (analytics-service)
 billing.events ─┘    groupId: analytics-service            │
                                                              ▼
                                                    DailyReportJob (Quartz)

 notification.requests ──► NotificationRequestConsumer ──► RabbitMQ ──► delivered
                            groupId: notification-service   (notification-service)
```

audit-service and analytics-service consume the same five topics; notification-service
consumes a different one entirely (`notification.requests`, populated by appointment-service's
reminder job and emr-service — see [Service Communication](communication.md)), and is the only
one of the three that hands off to a second broker (RabbitMQ) instead of writing straight to
its own database.

---

## notification-service

The only one of the three with a *second* retry layer, because delivery happens on RabbitMQ,
not directly in the Kafka consumer:

```text
Kafka: notification.requests
        │
        ▼
NotificationRequestConsumer ── KafkaDlqConfig (shared pattern above)
        │
        ▼
notification.exchange ──► notification.queue
        │
        ▼
NotificationJobListener
   │
   ├── success ──► NotificationLog("SENT") ──► delivered (email / SMS / push — simulated today)
   │
   └── failure ──► NotificationLog("FAILED"), rethrow
              │
              ▼
        nacked ──dead-lettered──► notification.retry.queue
                                   (no consumer, 30s message TTL)
                                          │
                                   TTL expires ──dead-lettered──► back to notification.exchange
                                                                    (redelivered — loop repeats)

        notification.dlq.queue exists as the intended terminal stop, reachable only if
        something explicitly routes a message there after N redeliveries.
```

**The gap:** `RabbitMqConfig`'s own class comment describes exactly that terminal step —
capping retries via the RabbitMQ `x-death` header and publishing to `notification.dlq.queue`
after N redeliveries, "checked in the listener." `NotificationJobListener` doesn't check that
header. Nothing currently reads `x-death` or publishes to the DLQ queue at all. In practice: a
permanently-failing notification (a bad template code, a malformed payload — anything that
throws on every attempt) cycles through the 30-second retry loop indefinitely rather than ever
landing somewhere for manual inspection.

**No delivery dedup.** `NotificationJobListener` sends first, logs after. If the process dies
between "sent" and "logged," or the same Kafka record gets redelivered from the consumer side,
the notification goes out again — there's no idempotency key stopping a duplicate send.

---

## audit-service

The simplest of the three — one handler shape repeated five times, one per topic:

```text
Kafka: patient.events, doctor.events, appointment.events, emr.events, billing.events
        │  (one @KafkaListener method per topic, all delegating to the same record(...))
        ▼
DomainEventAuditConsumer ── KafkaDlqConfig (shared pattern above)
        │
        ▼
AuditRecord(id=@GeneratedValue, eventType, aggregateId, sourceTopic, payload as raw JSON)
        │
        ▼
audit_db — append-only (no update/delete exposed)
        ▲
        │
AuditArchivalJob (@Scheduled) — prunes records past the retention window
```

Storing the raw payload as JSON rather than a typed model is deliberate — this service doesn't
need to understand a producer's schema to audit that something happened.

**The gap:** `AuditRecord.id` is a fresh UUID on every save, not derived from the source
event's own id. A Kafka redelivery of the same record (e.g. a crash between the DB commit and
the offset commit — a normal at-least-once scenario, not a bug) produces a **second**
`AuditRecord` row with identical content, not an upsert. For a log built to never silently drop
an event, that's the accepted tradeoff — but it means "count the rows" isn't the same as "count
the events."

---

## analytics-service

Consumes the same five topics as audit-service, but counts instead of recording:

```text
Kafka: patient.events, doctor.events, appointment.events, emr.events, billing.events
        │  (one @KafkaListener covering all five)
        ▼
DomainEventAnalyticsConsumer ── KafkaDlqConfig (shared pattern above)
        │
        ▼
EventCounterRepository.incrementCount(eventType, today)
   INSERT ... ON CONFLICT (event_type, counter_date) DO UPDATE SET count = count + 1
        │
        ▼
analytics_db

QuartzConfig + AutowiringSpringBeanJobFactory
   (lets a Quartz-managed job still be a Spring bean — constructor injection, etc.)
        │
        ▼
DailyReportJob — persistent; survives a restart mid-run, unlike Spring's @Scheduled
        │
        ▼
analytics_db (DailyReport)
```

**The gap:** the native upsert is atomic against *concurrent* writers — two consumer threads
incrementing the same `(eventType, day)` row at once can't lose an update to each other. It is
**not** deduplicated against *redelivery* — the same Kafka record processed twice increments
the counter twice. Same root cause as audit-service's duplicate rows, different symptom: here
it inflates a number instead of duplicating a record.

---

## At-Least-Once, Compared

All three sit on the same underlying guarantee — Kafka delivers at-least-once, and none of the
three adds its own idempotency key to collapse a redelivery back down to one effect — but what
that costs differs by service:

| Service | Symptom of redelivery | Why it's an accepted tradeoff here |
| --- | --- | --- |
| `audit-service` | A duplicate `AuditRecord` row with the same content | An audit log that occasionally double-writes is safer than one that silently drops an event under retry |
| `analytics-service` | A counter one (or more) higher than the true event count | Counters are directional/trend data, not a billing-grade exact count |
| `notification-service` | The same notification delivered twice — and, separately, a permanently-failing one retries forever instead of reaching the DLQ | No dedup key on send; the DLQ escape hatch described in `RabbitMqConfig`'s comment isn't wired up in the listener |

None of this is unique to a bad implementation — it's the normal shape of at-least-once
delivery without an idempotency layer, which is itself a real, named gap on the platform's
roadmap (see [`../../PROGRESS.md`](../../PROGRESS.md#production-roadmap): "Idempotent Kafka
consumers / Inbox Pattern"). This doc exists so that gap is legible per-service instead of
abstract.

---

See [`../reliability/README.md`](../reliability/README.md) for the platform-wide reliability
mechanisms (the transactional outbox on the *producing* side, rate limiting, circuit
breakers), and [Service Communication](communication.md) for the full event topology these
three sit downstream of.
