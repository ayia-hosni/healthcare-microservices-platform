# ADR-005: Two scenarios for Kafka vs RabbitMQ

## Status
Accepted (implemented)

## Context
The platform runs both Kafka and RabbitMQ. That looks redundant until you split by what each
message actually *is*: a broadcast fact that many services may care about (event), or a job
that exactly one worker must complete (task). This ADR documents the two scenarios in this
codebase that motivate each broker, so the choice isn't re-litigated per service.

## Scenario 1 — Kafka as the event backbone (choreographed saga fan-out)

**Use case:** appointment-service creates a booking. Nobody who publishes that fact should
need to know who's listening, and more than one service legitimately needs the same event.

**Flow:**
```
appointment-service (AppointmentEventPublisher)
        │  publishes AppointmentCreatedEvent
        ▼
   Kafka topic: appointment.events
        │
        ├──▶ billing-service      (AppointmentEventConsumer, group "billing-service")
        │       → generates a pending Invoice, publishes InvoiceGeneratedEvent
        ├──▶ notification-service (consumes via notification.requests / see Scenario 2)
        ├──▶ audit-service        (DomainEventAuditConsumer)  → immutable audit log
        └──▶ analytics-service    (DomainEventAnalyticsConsumer) → rolling EventCounter
```

**Why Kafka fits:**
- **Multiple independent consumers, no fan-out code in the producer.** appointment-service
  publishes once; four services read the same topic via their own consumer group
  (`Topics.APPOINTMENT_EVENTS`). Adding a 5th subscriber (e.g. a future loyalty-points
  service) touches nothing here — this is the choreographed saga from
  [[0003-choreographed-saga]].
- **Replayable log, not a queue.** A message stays on the topic per its retention policy, so
  a new consumer group can join later and read history; RabbitMQ queues delete a message once
  acked.
- **At-least-once + idempotent consumers.** `AppointmentEventConsumer` guards with
  `invoiceRepository.findAllByAppointmentId(...)` before writing, because Kafka redelivery on
  rebalance/retry is expected, not exceptional.

## Scenario 2 — RabbitMQ as the task queue (notification delivery)

**Use case:** once a `NotificationRequestedEvent` exists, exactly one worker should send that
email/SMS/push — never zero, never twice-with-different-outcomes — and a transient provider
failure (SES/Twilio timeout) should retry with backoff instead of being dropped or replayed
from the start of a Kafka topic.

**Flow:**
```
   Kafka topic: notification.requests
        │  @KafkaListener, group "notification-service"
        ▼
NotificationRequestConsumer  ──▶  RabbitMQ: notification.exchange → notification.queue
                                              │
                                              ▼
                                  NotificationJobListener (@RabbitListener)
                                    ├─ success → NotificationLog(SENT)
                                    └─ throws  → nacked
                                                   │
                                                   ▼
                                    notification.retry.queue (30s TTL, no consumer)
                                                   │  expires → dead-lettered back to
                                                   ▼
                                         notification.exchange  (redelivery)
                                                   │  after N attempts (x-death header)
                                                   ▼
                                         notification.dlq.queue  (manual inspection)
```

**Why RabbitMQ fits:**
- **Consumed-once work-item semantics.** A queue message is a job, not a fact — once
  `NotificationJobListener` acks it, it's gone. That's the right model for "send this SMS,"
  wrong for "the appointment was booked."
- **Native retry/DLQ topology.** `RabbitMqConfig` builds delayed-retry (dead-letter exchange +
  TTL queue) and a true DLQ using only core AMQP primitives — no plugin, no polling. Doing
  delayed redelivery on a Kafka topic would mean either a sleep-and-reprocess loop or a
  separate delay-topic scheme; Rabbit gets it from `x-dead-letter-exchange` + `x-message-ttl`.
- **Per-worker prefetch / backpressure.** Rabbit's consumer prefetch limits in-flight jobs per
  worker instance, which matters for a task queue (don't overload one SMS-sending pod) in a
  way that doesn't map cleanly onto Kafka's partition-per-consumer model.

## Decision
Kafka carries domain **events** (broadcast, replayable, many-to-many) between services.
RabbitMQ carries **jobs** (point-to-point, consumed-once, need retry/DLQ) inside a single
service's execution boundary. `NotificationRequestConsumer` is the one place that
deliberately crosses from one model to the other, and it's kept as a thin bridge for exactly
that reason.

## Consequences
+ Each broker is used for the semantics it's actually good at, instead of forcing one tool to
  do both jobs badly (e.g. simulating a DLQ on Kafka, or re-broadcasting a Rabbit queue).
+ New services default to publishing domain events on Kafka; only a service that owns a
  "deliver this exactly once, retry on failure" job (like outbound notifications) should reach
  for RabbitMQ.
- Two brokers is two things to operate, monitor, and reason about correlation IDs across (see
  `CorrelationIdFilter` in `common`). Acceptable at this service count; would be worth
  revisiting if a third broker-shaped need shows up.
