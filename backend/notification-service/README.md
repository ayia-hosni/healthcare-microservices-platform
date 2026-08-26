# 📬 notification-service

> **Kafka-to-RabbitMQ bridging and asynchronous email/SMS/push delivery for the Healthcare
> Platform.**

**Port:** `8087`
**Database:** `notification_db`
**Database role:** `notification_user`
**Domain events:** consumes `notification.requests`
**Consumer group:** `notification-service`

The only service on the platform that bridges two different messaging systems — Kafka in,
RabbitMQ out — and the only one of the three Supporting Services with no REST API at all. Pure
consumer/worker.

---

## ✨ Capabilities

| Capability                  | Description                                                                 |
| ------------------------------| --------------------------------------------------------------------- |
| 🌉 Kafka → RabbitMQ bridge     | Domain events (broadcast/replayable) become RabbitMQ jobs (consumed-once) |
| 📨 Multi-channel delivery      | Email, SMS, or push, dispatched by the event's `channel` field           |
| 🔁 Two independent retry layers | Kafka-side and RabbitMQ-side retry/DLQ, with different failure causes    |
| ⏱️ Delayed-retry via TTL, no plugin | A 30-second message-TTL queue produces backoff without RabbitMQ's delay plugin |
| 📝 Send-attempt logging        | Every attempt — success or failure — is recorded in `notification_logs` |

---

## 🎯 What It Does

No REST API — `NotificationServiceApplication` exposes no `@RestController` at all.
Everything happens off the back of `notification.requests`:

```text
Kafka: notification.requests
        │  populated by appointment-service's AppointmentReminderJob (hourly)
        │  and emr-service's document-upload flow (see their own READMEs)
        ▼
this service
```

### Channel dispatch

```java
switch (event.channel()) {
    case "EMAIL" -> sendEmail(event);
    case "SMS"   -> sendSms(event);
    case "PUSH"  -> sendPush(event);
    default      -> throw new IllegalArgumentException("Unknown channel: " + event.channel());
}
```

Sends are currently simulated — logged, not dispatched to a real provider. An unrecognized
channel value throws, which (per the retry topology below) routes the message into the same
retry loop as a genuine delivery failure.

---

# 🏗️ Architecture

Two hops, two brokers, two independent failure-handling layers.

```text
══════════════════════════════════════════════════════════════════════════════════════════════
                                 NOTIFICATION SERVICE
══════════════════════════════════════════════════════════════════════════════════════════════


    ┌────────────────────────────────────────────────────────────────────────────────────┐
    │                  1. KAFKA HOP — BRIDGE INTO RABBITMQ                              │
    └────────────────────────────────────────────────────────────────────────────────────┘


    Kafka: notification.requests
              │
              ▼
    ┌──────────────────────────────────────────────────────┐
    │             NotificationRequestConsumer                │
    │             @KafkaListener, groupId=notification-service│
    └──────────────────┬─────────────────────────────────────┘
                       │
                       ▼
        parse envelope ──► extract "payload" ──► deserialize as
        NotificationRequestedEvent (recipientId, channel, templateCode, payloadJson)
                       │
                       ▼
        rabbitTemplate.convertAndSend(EXCHANGE, QUEUE, payload)

        ⚠️ No try/catch here, no NotificationLog write at this stage —
           a parse failure or unreachable RabbitMQ propagates straight
           to KafkaDlqConfig's error handler (below); the FIRST log
           entry for this notification is written later, in step 2,
           only once an actual send is attempted


              │
              ▼ on failure (propagated exception)
    ┌──────────────────────────────────────────────┐
    │                KafkaDlqConfig                  │
    │  DefaultErrorHandler + FixedBackOff(1000L, 2)  │
    │  1 initial attempt + 2 retries, 1s apart        │
    │  ──► DeadLetterPublishingRecoverer               │
    │  ──► notification.requests.DLT                   │
    └──────────────────────────────────────────────┘
       (this service has no other Kafka producer —
        this DLQ publisher is the only Kafka output
        notification-service has)


══════════════════════════════════════════════════════════════════════════════════════════════


    ┌────────────────────────────────────────────────────────────────────────────────────┐
    │           2. RABBITMQ HOP — ACTUAL DELIVERY, RETRY, DEAD-LETTER                   │
    └────────────────────────────────────────────────────────────────────────────────────┘


    notification.exchange ──► notification.queue
                                     │
                                     ▼
                        ┌──────────────────────────┐
                        │  NotificationJobListener │
                        │  @RabbitListener(QUEUE)  │
                        └────────────┬─────────────┘
                                     │
                                     ▼
                    switch(event.channel()) { EMAIL | SMS | PUSH }
                                     │
                        ┌─────────────┴─────────────┐
                        ▼                            ▼
                     success                       failure (incl.
                        │                        unknown channel)
                        ▼                            │
              NotificationLog("SENT")                 ▼
                    saved                  NotificationLog("FAILED")
                                                       saved, then RETHROW
                                                       (message nacked)
                                                       │
                                                       ▼
                                    dead-lettered ──► notification.retry.queue
                                                       (no consumer, 30s message TTL)
                                                       │
                                             TTL expires ──dead-lettered──►
                                             back to notification.exchange
                                             (redelivered — the loop repeats)

                                    notification.dlq.queue exists as the intended
                                    terminal stop — but see Advanced below: nothing
                                    currently routes a message there
```

---

# 📦 Package Structure

```text
notification-service/
└── src/main/java/.../notification/
    │
    ├── messaging/
    │   ├── NotificationRequestConsumer   — Kafka → RabbitMQ bridge
    │   └── NotificationJobListener       — RabbitMQ consumer that actually "sends"
    │
    ├── config/
    │   ├── RabbitMqConfig                — exchange/queue/DLQ topology
    │   └── KafkaDlqConfig                — Kafka-side retry/DLQ
    │
    ├── repository/
    │   └── NotificationLogRepository
    │
    └── domain/
        └── NotificationLog   (recipientId, channel, templateCode, status, sentAt)
```

No `web` package — this service has no REST API of its own.

---

# 🌉 Why Two Brokers, Not One

```text
                     Kafka                          RabbitMQ
              ─────────────────────         ─────────────────────
              Domain event backbone          Job/task queue
              Broadcast — every interested   Consumed-once — one worker
              consumer group gets its own    handles a message, then it's gone
              copy, replayable from offset
              Used for: patient.events,      Used for: notification delivery
              doctor.events, appointment.    retry/backoff/DLQ specifically —
              events, billing.events,        NOT a general-purpose bus (see
              emr.events, notification.      ADR-0002)
              requests
```

`NotificationRequestConsumer`'s own class comment states the reasoning directly: using both
brokers isn't redundant. Kafka is right for *broadcasting* that a notification should happen
(other consumer groups could exist for the same topic without this service knowing); RabbitMQ
is right for *executing* the actual send-with-retry work, since that's a consumed-once job, not
a fact multiple parties need to independently observe.

---

# ⚠️ Advanced: The DLQ Escape Hatch That Isn't Wired Up

`RabbitMqConfig`'s own class comment describes a complete design: after a message has cycled
through the 30-second retry loop enough times (tracked via RabbitMQ's `x-death` header,
"checked in the listener"), it should be routed to `notification.dlq.queue` — the true
terminal dead-letter queue, separate from the retry queue's temporary holding role.

**`NotificationJobListener` doesn't check that header.** Nothing in this service currently
reads `x-death` or explicitly routes a message to `notification.dlq.queue`. In practice: a
notification that fails on *every* attempt — a malformed payload, an unrecognized channel
value, anything that throws unconditionally — cycles through the 30-second
nack-TTL-expire-redeliver loop indefinitely, rather than ever reaching a queue meant for manual
inspection. There's no dead letter to alert on; there's an infinite retry.

---

# ⚠️ Advanced: At-Least-Once, No Delivery Dedup

`NotificationJobListener` writes its `NotificationLog` entry *after* attempting the send, not
before — `sendEmail(...)` (etc.) runs, then `NotificationLog("SENT")` is saved. If the process
crashes in the gap between "the send happened" and "the log committed," or the same RabbitMQ
message is redelivered for any other reason, the notification is dispatched again with no
idempotency key stopping the duplicate. The database write is a receipt, not a lock — it
doesn't have a role in *preventing* the resend, only recording that a send was attempted.

This is the same at-least-once root cause the other two Supporting Services hit — see
[`../../docs/architecture/supporting-services.md`](../../docs/architecture/supporting-services.md) for the platform-wide
comparison (`audit-service` gets a duplicate row, `analytics-service` gets an inflated counter,
this service gets a duplicate send).

---

# 🚀 Where This Is Headed

Wiring the `x-death` check into `NotificationJobListener` closes the DLQ gap above with no
change to `RabbitMqConfig`'s existing topology — the queue and routing already exist, only the
listener-side check is missing. Delivery channel selection becomes configurable per
notification type and per recipient rather than the current fixed default, and real
SES/Twilio/FCM calls replace the simulated (logged) sends in `sendEmail`/`sendSms`/`sendPush`
without touching the retry/DLQ plumbing around them.

---

See [`../../docs/reliability/README.md`](../../docs/reliability/README.md) for the full retry/DLQ flow, and
[`../../docs/architecture/supporting-services.md`](../../docs/architecture/supporting-services.md) for how this service compares
to `audit-service` and `analytics-service`.
