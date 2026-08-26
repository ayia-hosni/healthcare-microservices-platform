# 📊 analytics-service

> **Event-driven analytics, daily aggregation, and durable scheduled reporting for the Healthcare Platform.**

**Port:** `8089`
**Database:** `analytics_db`
**Database role:** `analytics_user`
**Consumer group:** `analytics-service`

The Analytics Service consumes domain events from across the platform, aggregates them into daily event counters, exposes administrative analytics queries, and generates persistent daily reports using Quartz.

It is intentionally designed for **operational and trend analytics**, where eventual consistency and approximate counts are acceptable, rather than as a source of billing-grade or audit-grade data.

---

## ✨ Capabilities

| Capability             | Description                                                                    |
| ---------------------- | ------------------------------------------------------------------------------ |
| 📥 Event consumption   | Consumes domain events from five Kafka topics                                  |
| 📈 Daily counters      | Aggregates events by event type and calendar day                               |
| 🔐 Admin analytics API | Exposes daily counters to authorized administrators                            |
| ⚡ Atomic aggregation   | Uses PostgreSQL upsert semantics for concurrent-safe counter increments        |
| 🔁 Retry and recovery  | Retries failed Kafka consumption before routing records to a dead-letter topic |
| 🕐 Durable reporting   | Uses Quartz for persistent, restart-aware daily report generation              |
| 💾 Historical reports  | Stores generated daily reports in PostgreSQL as JSON summaries                 |

---

## 🎯 What It Does

### Administrative counter lookup

```http
GET /counters?date=YYYY-MM-DD
```

Access is restricted to administrators:

```java
@PreAuthorize("hasRole('ADMIN')")
```

The endpoint returns the event counters recorded for the requested date.

### Event-driven aggregation

The service consumes events from:

```text
patient.events
doctor.events
appointment.events
emr.events
billing.events
```

For every successfully processed event, the service extracts the `eventType` and increments the counter for:

```text
(eventType, counterDate)
```

For example:

```text
PATIENT_CREATED      2026-08-26      154
APPOINTMENT_BOOKED   2026-08-26      89
BILLING_COMPLETED    2026-08-26      42
```

### Daily reporting

A durable Quartz job periodically reads the previous day's counters and persists a summarized report.

A stored report conceptually looks like:

```json
{
  "PATIENT_CREATED": 154,
  "APPOINTMENT_BOOKED": 89,
  "EMR_UPDATED": 231,
  "BILLING_COMPLETED": 42
}
```

---

# 🏗️ Architecture

The service has three primary data flows:

1. **REST** — synchronous administrative counter queries.
2. **Kafka** — asynchronous event consumption and aggregation.
3. **Quartz** — durable scheduled generation of daily reports.

```text
══════════════════════════════════════════════════════════════════════════════════════════════
                                  ANALYTICS SERVICE
══════════════════════════════════════════════════════════════════════════════════════════════


    ┌────────────────────────────────────────────────────────────────────────────────────┐
    │                         1. REST — COUNTER QUERIES                                  │
    └────────────────────────────────────────────────────────────────────────────────────┘

        Client
          │
          │ GET /counters?date=YYYY-MM-DD
          ▼
    ┌─────────────────────────────────────┐
    │       AnalyticsController           │
    │                                     │
    │ @PreAuthorize("hasRole('ADMIN')")   │
    └──────────────────┬──────────────────┘
                       │
                       ▼
    ┌──────────────────────────────────────────────┐
    │         EventCounterRepository              │
    │                                              │
    │ findAllByCounterDate(date)                   │
    └──────────────────┬───────────────────────────┘
                       │
                       ▼
    ┌──────────────────────────────────────────────┐
    │         EventCounterResponse                 │
    │                                              │
    │ eventType + date + count                     │
    └──────────────────┬───────────────────────────┘
                       │
                       ▼
                    Client



══════════════════════════════════════════════════════════════════════════════════════════════


    ┌────────────────────────────────────────────────────────────────────────────────────┐
    │                     2. KAFKA — EVENT-DRIVEN AGGREGATION                           │
    └────────────────────────────────────────────────────────────────────────────────────┘


    patient.events ────────┐
    doctor.events ─────────┤
    appointment.events ────┤
    emr.events ────────────┤
    billing.events ────────┘
                            │
                            ▼
              ┌───────────────────────────────────┐
              │ DomainEventAnalyticsConsumer      │
              │                                   │
              │ @KafkaListener                    │
              │ groupId = "analytics-service"     │
              └─────────────────┬─────────────────┘
                                │
                                │ Reads raw event payload
                                │
                                ▼
                     ┌─────────────────────┐
                     │ Extract eventType   │
                     │ from JSON payload   │
                     └──────────┬──────────┘
                                │
                                ▼
                     ┌─────────────────────┐
                     │ Increment counter   │
                     │ for today's date    │
                     └──────────┬──────────┘
                                │
                                ▼
          ┌─────────────────────────────────────────────┐
          │ EventCounterRepository.incrementCount(...)  │
          │                                             │
          │ INSERT ... ON CONFLICT ... DO UPDATE        │
          │                                             │
          │ count = count + 1                           │
          └──────────────────────┬──────────────────────┘
                                 │
                                 ▼
                  ┌──────────────────────────────┐
                  │        EventCounter          │
                  │                              │
                  │ id                           │
                  │ eventType                    │
                  │ counterDate                  │
                  │ count                        │
                  └──────────────┬───────────────┘
                                 │
                                 ▼
                    analytics_db.event_counters

                    UNIQUE(event_type, counter_date)


══════════════════════════════════════════════════════════════════════════════════════════════


    ┌────────────────────────────────────────────────────────────────────────────────────┐
    │                    3. KAFKA FAILURE RECOVERY & DLQ                                │
    └────────────────────────────────────────────────────────────────────────────────────┘


                  DomainEventAnalyticsConsumer
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


══════════════════════════════════════════════════════════════════════════════════════════════


    ┌────────────────────────────────────────────────────────────────────────────────────┐
    │                 4. QUARTZ — DURABLE DAILY REPORTING                               │
    └────────────────────────────────────────────────────────────────────────────────────┘


              ┌────────────────────────────────────────┐
              │ QuartzConfig                           │
              │                                        │
              │ AutowiringSpringBeanJobFactory         │
              └──────────────────┬─────────────────────┘
                                 │
                                 ▼
                    ┌─────────────────────────┐
                    │     Quartz Scheduler    │
                    └────────────┬────────────┘
                                 │
                                 ▼
                    ┌─────────────────────────┐
                    │     DailyReportJob      │
                    │                         │
                    │ implements Job          │
                    │                         │
                    │ Quartz owns lifecycle   │
                    └────────────┬────────────┘
                                 │
                                 │ For yesterday
                                 ▼
        ┌────────────────────────────────────────────────────┐
        │ EventCounterRepository.findAllByCounterDate(...)   │
        └──────────────────────┬─────────────────────────────┘
                               │
                               ▼
                    ┌─────────────────────────┐
                    │ Group by event type     │
                    │                         │
                    │ Map<eventType, count>   │
                    └────────────┬────────────┘
                                 │
                                 ▼
                    ┌─────────────────────────┐
                    │ Serialize summary       │
                    │ to JSON                 │
                    └────────────┬────────────┘
                                 │
                                 ▼
                    ┌─────────────────────────┐
                    │ DailyReportRepository   │
                    │ .save(...)              │
                    └────────────┬────────────┘
                                 │
                                 ▼
                    ┌─────────────────────────┐
                    │      DailyReport        │
                    │                         │
                    │ id                      │
                    │ reportDate              │
                    │ summaryJson             │
                    │ generatedAt             │
                    └────────────┬────────────┘
                                 │
                                 ▼
                     analytics_db.daily_reports

                        UNIQUE(report_date)
```

---

# 📦 Package Structure

```text
analytics-service/
└── src/main/java/.../analytics/
    │
    ├── config/
    │   ├── KafkaDlqConfig
    │   ├── QuartzConfig
    │   └── AutowiringSpringBeanJobFactory
    │
    ├── web/
    │   └── AnalyticsController
    │
    ├── messaging/
    │   └── DomainEventAnalyticsConsumer
    │
    ├── scheduler/
    │   └── DailyReportJob
    │
    ├── repository/
    │   ├── EventCounterRepository
    │   └── DailyReportRepository
    │
    └── domain/
        ├── EventCounter
        └── DailyReport
```

---

# ⚡ Event Aggregation

## A lightweight event consumer

`DomainEventAnalyticsConsumer` listens to all five domain-event topics through the `analytics-service` consumer group.

The consumer does not need to deserialize every domain event into a service-specific Java model.

Instead, it extracts the information required for analytics aggregation:

```text
Raw Kafka Event
       │
       ▼
Read eventType
       │
       ▼
Determine counter date
       │
       ▼
Atomically increment aggregate
```

This keeps the analytics service loosely coupled to the detailed schemas owned by the producing services.

The analytics concern is the event's classification and occurrence—not the complete business payload.

---

## Atomic PostgreSQL upsert

Counters are stored using a unique key:

```text
(event_type, counter_date)
```

The aggregation operation uses an atomic PostgreSQL upsert pattern:

```sql
INSERT INTO event_counters (...)
VALUES (gen_random_uuid(), :eventType, :today, 1)

ON CONFLICT (event_type, counter_date)
DO UPDATE SET count = event_counters.count + 1;
```

This is important because multiple Kafka consumer threads or concurrent processing paths may attempt to increment the same counter.

The database, rather than application-level read-modify-write logic, owns the concurrency-sensitive increment.

```text
Consumer A ──┐
             ├──► Atomic PostgreSQL upsert ──► count + 1
Consumer B ──┘
```

This avoids lost updates caused by patterns such as:

```text
SELECT count
    ↓
count + 1
    ↓
UPDATE count
```

---

# 🔁 Failure Handling and Dead-Letter Topics

Kafka consumption uses Spring Kafka's error handling infrastructure:

```text
DefaultErrorHandler
    +
FixedBackOff(1000L, 2)
    +
DeadLetterPublishingRecoverer
```

The processing sequence is:

```text
Attempt 1
   │
   ├── Success ───────────────► Commit processing
   │
   └── Failure
          │
          ▼
       Retry 1
          │
          ▼
       Retry 2
          │
          ▼
       Still failing
          │
          ▼
   Publish to <topic>.DLT
```

A failed message receives:

* **1 initial processing attempt**
* **2 retries**
* **1-second delay between retries**

After recovery is exhausted, the record is published to the corresponding dead-letter topic.

The Analytics Service does not act as a general event producer. Its Kafka publishing responsibility is limited to failure recovery through the dead-letter publishing mechanism.

---

# 🕐 Why Quartz Instead of `@Scheduled`?

The platform uses both Spring's `@Scheduled` support and Quartz, depending on the reliability requirements of the workload.

The daily report job is implemented with Quartz because it represents a persistent scheduled operation rather than lightweight periodic background work.

```text
Lightweight periodic task
        │
        └──► Spring @Scheduled


Durable scheduled business operation
        │
        └──► Quartz
```

## Quartz job lifecycle

Quartz creates `Job` instances itself.

That means the job is not managed like a normal Spring `@Component`.

```text
Quartz Scheduler
      │
      ▼
Creates DailyReportJob
      │
      ▼
AutowiringSpringBeanJobFactory
      │
      ▼
Inject Spring-managed dependencies
      │
      ▼
Execute job
```

This is why `DailyReportJob` uses Spring dependency injection through the custom job factory integration rather than following the normal Spring bean lifecycle.

The job:

1. Determines the reporting date.
2. Loads the corresponding event counters.
3. Groups them into a summary.
4. Serializes the summary to JSON.
5. Persists the generated report.

The result is stored as a `DailyReport`:

```text
DailyReport
├── id
├── reportDate
├── summaryJson
└── generatedAt
```

A unique constraint on `reportDate` ensures the persisted reporting model maintains one report per reporting date.

---

# ⚠️ Analytics Consistency Model

## Counters are trend-oriented, not exactly-once metrics

The Analytics Service consumes Kafka events using an at-least-once delivery model.

The database upsert guarantees that concurrent increments are handled safely, but it does **not** provide event-level deduplication.

Consider this scenario:

```text
Kafka delivers Event X
        │
        ▼
Analytics increments counter
        │
        ▼
Failure occurs before offset is safely committed
        │
        ▼
Kafka redelivers Event X
        │
        ▼
Analytics increments counter again
```

Result:

```text
Expected count:  100
Actual count:    101
```

The counter is therefore best interpreted as:

* operational analytics
* traffic trends
* activity patterns
* dashboard metrics
* approximate daily volumes

It should **not** be treated as the authoritative source for:

* billing
* financial reconciliation
* exact audit records
* legally significant counts

This trade-off keeps the consumer simple and the aggregation path efficient.

The same underlying at-least-once delivery characteristic can affect other event-consuming supporting services differently. For example, a consumer that persists every received event may produce duplicate records rather than an inflated aggregate.

For the comparison across Kafka-consuming supporting services, see [`../../docs/architecture/supporting-services.md`](../../docs/architecture/supporting-services.md).

---

# 📖 Consumer Behavior

The Analytics Service uses:

```text
Consumer group:      analytics-service
Offset reset policy: earliest
```

When this consumer group has no committed offsets for a topic, it starts reading from the earliest available records rather than only processing newly produced events.

```text
Kafka Topic
│
├── Historical events
├── Historical events
├── Historical events
├── Historical events
└── New events
    ▲
    │
    └── Fresh analytics-service consumer group
        starts from earliest
```

This behavior is useful when initializing or rebuilding analytics from retained event history.

The availability of historical replay is still bounded by Kafka topic retention and the existence of the underlying records.

---

# 🗄️ Data Model

## `event_counters`

Stores aggregated daily event counts.

```text
event_counters
├── id
├── event_type
├── counter_date
└── count

UNIQUE(event_type, counter_date)
```

Example:

| Event Type           | Date         | Count |
| -------------------- | ------------ | ----: |
| `PATIENT_CREATED`    | `2026-08-25` |   154 |
| `APPOINTMENT_BOOKED` | `2026-08-25` |    89 |
| `EMR_UPDATED`        | `2026-08-25` |   231 |

---

## `daily_reports`

Stores generated reporting snapshots.

```text
daily_reports
├── id
├── report_date
├── summary_json
└── generated_at

UNIQUE(report_date)
```

The JSON summary allows the report structure to evolve without requiring a new relational column for every event type.

---

# 🔮 Reporting Evolution

The service provides the foundation for progressively richer analytics.

The current event aggregation model naturally supports:

```text
Domain Events
      │
      ▼
Daily
```
