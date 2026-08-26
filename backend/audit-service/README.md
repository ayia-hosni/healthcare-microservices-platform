# 🧾 audit-service

> **Platform-wide, append-only domain event auditing for the Healthcare Platform.**

**Port:** `8088`
**Database:** `audit_db`
**Database role:** `audit_user`
**Consumer group:** `audit-service`

The Audit Service subscribes to every domain-event topic currently produced on the platform,
records each one verbatim as an append-only entry, exposes an administrative query API over
that trail, and periodically archives records past their retention window.

It is intentionally designed to **capture what happened, not interpret it** — every topic is
handled by the same code path, so adding a new domain event type anywhere on the platform
doesn't require a corresponding code change here.

---

## ✨ Capabilities

| Capability                 | Description                                                                 |
| --------------------------- | ----------------------------------------------------------------------------- |
| 📥 Platform-wide ingestion  | Consumes domain events from all five Kafka topics the platform produces      |
| 📜 Append-only recording    | No update or delete path is exposed — records are write-once                 |
| 🔍 Admin query API          | Exposes the audit trail for a given aggregate to authorized administrators   |
| 🔁 Retry and recovery       | Retries failed Kafka consumption before routing records to a dead-letter topic |
| 🗄️ Scheduled archival       | Deletes records past a 365-day retention window on a daily cron              |
| 🧩 Schema-agnostic capture  | Stores each event's payload as raw JSON — no per-event-type Java model needed |

---

## 🎯 What It Does

### Query the audit trail for an aggregate

```http
GET /api/v1/audit/by-aggregate/{aggregateId}
```

Access is restricted to administrators:

```java
@PreAuthorize("hasRole('ADMIN')")
```

Returns every recorded event for that aggregate, oldest and newest alike — there's no
pagination or date filtering on this endpoint today.

### Platform-wide event ingestion

The service consumes from every domain-event topic the platform currently produces:

```text
patient.events
doctor.events
appointment.events
emr.events
billing.events
```

Each Kafka record is a JSON envelope; the consumer pulls four fields out of it and stores the
fifth (`payload`) as-is:

```text
{ "eventType": "...", "aggregateId": "...", "correlationId": "...", "payload": { ... } }
        │                    │                     │                     │
        ▼                    ▼                     ▼                     ▼
  AuditRecord          AuditRecord           AuditRecord            AuditRecord
  .eventType           .aggregateId          .correlationId         .payloadJson
                                                                     (stored verbatim)
```

`sourceTopic` is set from which Kafka topic delivered the record, not from the envelope
itself.

### Scheduled archival

```text
cron: 0 30 3 * * *   (daily, 03:30)
retention: 365 days
```

Every night, records older than the retention window are deleted after their count is logged.

---

# 🏗️ Architecture

The service has four primary data flows:

1. **REST** — synchronous administrative queries over the audit trail.
2. **Kafka** — asynchronous, schema-agnostic event ingestion (five topics, one code path).
3. **DLQ** — failure recovery for ingestion.
4. **Scheduled archival** — retention enforcement.

```text
══════════════════════════════════════════════════════════════════════════════════════════════
                                    AUDIT SERVICE
══════════════════════════════════════════════════════════════════════════════════════════════


    ┌────────────────────────────────────────────────────────────────────────────────────┐
    │                       1. REST — AUDIT TRAIL QUERIES                                │
    └────────────────────────────────────────────────────────────────────────────────────┘

        Client
          │
          │ GET /api/v1/audit/by-aggregate/{aggregateId}
          ▼
    ┌─────────────────────────────────────┐
    │           AuditController           │
    │                                     │
    │  @PreAuthorize("hasRole('ADMIN')")  │
    └──────────────────┬──────────────────┘
                       │
                       ▼
    ┌──────────────────────────────────────────────┐
    │           AuditRecordRepository               │
    │                                                │
    │  findAllByAggregateId(aggregateId)             │
    └──────────────────┬───────────────────────────┘
                       │
                       ▼
    ┌──────────────────────────────────────────────┐
    │           AuditRecordResponse[]                │
    │                                                │
    │  id, eventType, aggregateId, sourceTopic,      │
    │  correlationId, payloadJson, recordedAt         │
    └──────────────────┬───────────────────────────┘
                       │
                       ▼
                    Client


══════════════════════════════════════════════════════════════════════════════════════════════


    ┌────────────────────────────────────────────────────────────────────────────────────┐
    │                 2. KAFKA — PLATFORM-WIDE EVENT INGESTION                           │
    └────────────────────────────────────────────────────────────────────────────────────┘


    patient.events ────────┐
    doctor.events ─────────┤
    appointment.events ────┼──►  one @KafkaListener method PER topic, all identical,
    emr.events ────────────┤     all delegating to the same private record(topic, rawEvent)
    billing.events ────────┘

                            │
                            ▼
              ┌───────────────────────────────────┐
              │      DomainEventAuditConsumer      │
              │                                     │
              │  @KafkaListener per topic           │
              │  groupId = "audit-service"          │
              │  @Transactional                     │
              └─────────────────┬─────────────────┘
                                │
                                ▼
                     ┌─────────────────────┐
                     │  Parse JSON envelope │
                     │  eventType           │
                     │  aggregateId         │
                     │  correlationId       │
                     │  payload             │
                     └──────────┬──────────┘
                                │
                                ▼
                     ┌─────────────────────┐
                     │  new AuditRecord(   │
                     │    eventType,       │
                     │    aggregateId,     │
                     │    sourceTopic,     │
                     │    correlationId,   │
                     │    payloadJson)     │
                     └──────────┬──────────┘
                                │
                                ▼
          ┌─────────────────────────────────────────────┐
          │        AuditRecordRepository.save(...)       │
          └──────────────────────┬──────────────────────┘
                                 │
                                 ▼
                  ┌──────────────────────────────┐
                  │          AuditRecord          │
                  │                                │
                  │  id            (@GeneratedValue)│
                  │  eventType                     │
                  │  aggregateId                   │
                  │  sourceTopic                   │
                  │  correlationId                 │
                  │  payloadJson    (raw TEXT)      │
                  │  recordedAt     (Instant.now()) │
                  └──────────────┬───────────────┘
                                 │
                                 ▼
                    audit_db.audit_records

                    (no @Modifying update/delete path exposed
                     anywhere in this service — append-only)


══════════════════════════════════════════════════════════════════════════════════════════════


    ┌────────────────────────────────────────────────────────────────────────────────────┐
    │                    3. KAFKA FAILURE RECOVERY & DLQ                                │
    └────────────────────────────────────────────────────────────────────────────────────┘


                  DomainEventAuditConsumer
                              │
                              ▼
                       Processing failure
                              │
                              ▼
                    ┌──────────────────────┐
                    │ DefaultErrorHandler  │
                    └──────────┬───────────┘
                               │
                               ▼
                    ┌──────────────────────┐
                    │ FixedBackOff         │
                    │                      │
                    │ delay: 1 second      │
                    │ retries: 2           │
                    └──────────┬───────────┘
                               │
                               ▼
                  1 Initial Attempt + 2 Retries
                               │
                         Still failing?
                               │
                              Yes
                               │
                               ▼
              ┌──────────────────────────────────┐
              │ DeadLetterPublishingRecoverer    │
              └────────────────┬─────────────────┘
                               │
                               ▼
                    <original-topic>.DLT

                  (this service has no domain-event
                   producer of its own — this DLQ
                   publisher is the only Kafka output
                   audit-service has)


══════════════════════════════════════════════════════════════════════════════════════════════


    ┌────────────────────────────────────────────────────────────────────────────────────┐
    │                    4. SCHEDULED ARCHIVAL & RETENTION                              │
    └────────────────────────────────────────────────────────────────────────────────────┘


              ┌────────────────────────────────────────┐
              │  @Scheduled(cron = "0 30 3 * * *")     │
              │  daily at 03:30                        │
              └──────────────────┬─────────────────────┘
                                 │
                                 ▼
                    ┌─────────────────────────┐
                    │     AuditArchivalJob    │
                    │                         │
                    │  RETENTION_DAYS = 365   │
                    └────────────┬────────────┘
                                 │
                                 ▼
        ┌────────────────────────────────────────────────────┐
        │  AuditRecordRepository.findAllByRecordedAtBefore(  │
        │      now - 365 days)                                │
        └──────────────────────┬─────────────────────────────┘
                               │
                               ▼
                    ┌─────────────────────────┐
                    │  TODO (production):     │
                    │  export to MinIO/S3     │
                    │  cold storage first     │
                    │  — not implemented yet  │
                    └────────────┬────────────┘
                                 │
                                 ▼
                    ┌─────────────────────────┐
                    │  deleteAll(old)         │
                    └────────────┬────────────┘
                                 │
                                 ▼
                       log.info(count deleted)
```

---

# 📦 Package Structure

```text
audit-service/
└── src/main/java/.../audit/
    │
    ├── config/
    │   └── KafkaDlqConfig
    │
    ├── web/
    │   ├── AuditController
    │   └── dto/
    │       └── AuditRecordResponse
    │
    ├── messaging/
    │   └── DomainEventAuditConsumer
    │
    ├── scheduler/
    │   └── AuditArchivalJob
    │
    ├── repository/
    │   └── AuditRecordRepository
    │
    └── domain/
        └── AuditRecord
```

---

# 📝 Event Ingestion

## One handler shape, five topics

`DomainEventAuditConsumer` has five separate `@KafkaListener` methods — one per topic — but
every one of them delegates immediately to the same private `record(topic, rawEvent)` method:

```text
onPatientEvent(...)      ─┐
onDoctorEvent(...)        │
onAppointmentEvent(...)   ├──► record(topic, rawEvent)   — identical logic, every topic
onEmrEvent(...)           │
onBillingEvent(...)      ─┘
```

That's deliberate, not an oversight: this service's job is to capture *that* something
happened, not interpret *what* it means. Branching per event type here would mean every new
domain event added anywhere on the platform also requires a code change in audit-service —
instead, adding a topic to the `@KafkaListener` annotation list is the only change needed.

## Storing the payload verbatim

Each Kafka record is a JSON envelope. `record(...)` pulls four fields out of it individually
and stores the fifth — `payload` — as raw text, not deserialized into any Java type:

```text
Raw Kafka record (JSON)
        │
        ▼
  Parse as a generic JsonNode
        │
        ├── eventType      ──► AuditRecord.eventType
        ├── aggregateId    ──► AuditRecord.aggregateId
        ├── correlationId  ──► AuditRecord.correlationId
        └── payload        ──► AuditRecord.payloadJson  (stored as-is, TEXT column)
```

This is what keeps audit-service decoupled from every producer's domain model — it never
needs to know a new event's shape to record it.

---

# 🔁 Failure Handling and Dead-Letter Topics

Kafka consumption uses the same Spring Kafka error-handling stack every Kafka-consuming
service on this platform uses:

```text
DefaultErrorHandler
    +
FixedBackOff(1000L, 2)
    +
DeadLetterPublishingRecoverer
```

```text
Attempt 1
   │
   ├── Success ───────────────► AuditRecordRepository.save(...)
   │
   └── Failure
          │
          ▼
       Retry 1  (after 1s)
          │
          ▼
       Retry 2  (after 1s)
          │
          ▼
       Still failing
          │
          ▼
   Publish to <topic>.DLT
```

audit-service has no Kafka producer of its own outside of this DLQ publisher — it's a pure
sink for every topic except the dead-letter ones its own failures create.

---

# 🗄️ Archival & Retention

`AuditArchivalJob` runs on a Spring `@Scheduled` cron trigger — `0 30 3 * * *`, daily at
03:30 — not Quartz. That's a real difference from analytics-service's `DailyReportJob`: this
job doesn't need to survive a restart mid-run the way a report generation job does; if a
nightly archival run is skipped once, the next night's run just deletes a slightly larger
batch, with no correctness impact.

```text
Every record older than 365 days
        │
        ▼
findAllByRecordedAtBefore(cutoff)
        │
        ▼
deleteAll(old)
        │
        ▼
log.info("Archived {} audit records...")
```

**A real, named gap, not glossed over:** the job's own code comment says records should be
exported to cold storage (MinIO/S3) before deletion, to satisfy audit-log retention
requirements while keeping the hot Postgres table small. That export doesn't happen today —
`archiveOldRecords()` deletes the batch directly after only logging its size. Records past the
365-day window are gone, not archived elsewhere.

---

# ⚠️ Audit Consistency Model

## At-least-once delivery, and no deduplication against it

`AuditRecord.id` is a fresh `@GeneratedValue` UUID assigned on every save — it isn't derived
from anything in the source Kafka record. Combined with Kafka's at-least-once delivery
guarantee, that means:

```text
Kafka delivers Event X
        │
        ▼
AuditRecord saved (id = A)
        │
        ▼
Failure occurs before the consumer's offset is committed
        │
        ▼
Kafka redelivers Event X
        │
        ▼
AuditRecord saved again (id = B, same content as A)
```

Result: two rows with identical `eventType`/`aggregateId`/`payloadJson`, different `id`s and
`recordedAt` timestamps — not an upsert, not a rejected duplicate.

For an append-only log built to never silently drop an event, an occasional duplicate on
retry is the accepted tradeoff — the alternative (deduplicating and risking a dropped event
under some edge case) is worse for what this service is for.

The same at-least-once root cause shows up differently in analytics-service — a redelivered
event there inflates a counter instead of duplicating a row. See
[`../../docs/architecture/supporting-services.md`](../../docs/architecture/supporting-services.md) for the comparison across all
three Kafka-consuming supporting services.

---

# 📖 Consumer Behavior

```text
Consumer group:      audit-service
Offset reset policy: earliest
```

When this consumer group has no committed offsets for a topic — a fresh deployment, or one
whose committed offsets were deleted — it starts reading from the earliest available record
on that topic rather than only newly produced events going forward.

```text
Kafka Topic
│
├── Historical events
├── Historical events
├── Historical events
└── New events
    ▲
    │
    └── Fresh audit-service consumer group starts from earliest
```
