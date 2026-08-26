# 📅 appointment-service

> **Appointment booking, cancellation, rescheduling, and the entry point to the platform's
> core cross-service workflow.**

**Port:** `8084`
**Database:** `appointment_db`
**Database role:** `appointment_user`
**Domain events:** publishes `appointment.events`, `notification.requests`

More cross-service traffic runs through this service than any other on the platform: it's the
synchronous caller of two other services' gRPC contracts, the producer every downstream
Supporting Service reacts to, and the one service whose booking endpoint sits behind an
explicit rate limiter.

---

## ✨ Capabilities

| Capability                  | Description                                                                |
| ------------------------------| --------------------------------------------------------------------- |
| 🚦 Rate-limited booking       | 50 requests/second, reject-immediately (no queueing) via resilience4j |
| 🔒 Idempotent booking          | A repeated request with the same key returns the original result, not a duplicate |
| 🔍 Synchronous pre-validation  | gRPC calls to patient-service/doctor-service before persisting anything |
| 🔐 Double-booking prevention   | A DB unique constraint is the real guarantee; a pessimistic lock is the fast-fail in front of it |
| 📤 Outbox-backed domain events | `AppointmentCreatedEvent`/`AppointmentCancelledEvent` commit atomically with the appointment row |
| ⏰ Reminder scheduling          | An hourly job finds confirmed appointments ~24h out and requests a notification |

---

## 🎯 What It Does

```http
POST   /api/v1/appointments                     hasAnyRole('ADMIN','PATIENT','NURSE')   rate-limited
GET    /api/v1/appointments/{id}
GET    /api/v1/appointments?patientId=...        hasAnyRole('ADMIN','PATIENT','DOCTOR','NURSE')
POST   /api/v1/appointments/{id}/cancel          hasAnyRole('ADMIN','PATIENT','DOCTOR','NURSE')
POST   /api/v1/appointments/{id}/reschedule      hasAnyRole('ADMIN','PATIENT','NURSE')
```

### Booking

```java
@RateLimiter(name = "bookingApi", fallbackMethod = "bookRejected")
```

```yaml
resilience4j.ratelimiter.instances.bookingApi:
  limit-for-period: 50
  limit-refresh-period: 1s
  timeout-duration: 0    # reject immediately over the limit — never makes a caller wait
```

Over the limit, `bookRejected` returns a bare `429` — this is booking-spam/thundering-herd
protection, not a correctness check, so the caller is expected to retry shortly.

### Idempotency

Every booking request carries a client-supplied `idempotencyKey`. A repeated request with the
same key short-circuits before any validation or persistence, returning the *original* result:

```java
var existing = appointmentRepository.findByIdempotencyKey(request.idempotencyKey());
if (existing.isPresent()) return toResponse(existing.get());
```

---

# 🏗️ Architecture

```text
══════════════════════════════════════════════════════════════════════════════════════════════
                                  APPOINTMENT SERVICE
══════════════════════════════════════════════════════════════════════════════════════════════


    ┌────────────────────────────────────────────────────────────────────────────────────┐
    │                    1. BOOKING — THE FULL SYNCHRONOUS PATH                          │
    └────────────────────────────────────────────────────────────────────────────────────┘

        Client
          │
          │ POST /api/v1/appointments   @hasAnyRole('ADMIN','PATIENT','NURSE')
          ▼
    ┌─────────────────────────────────────┐
    │        AppointmentController          │
    │                                       │
    │  @RateLimiter("bookingApi")           │
    │  over limit ──► bookRejected() ──► 429│
    └──────────────────┬──────────────────┘
                       │ within limit
                       ▼
    ┌──────────────────────────────────────────────────────────────────────┐
    │                     AppointmentService.book(request)                  │
    │                                                                        │
    │  1. findByIdempotencyKey(key) — already exists? return it, STOP HERE   │
    │                                                                        │
    │  2. BookingValidationClient.requirePatientExists(patientId)            │
    │       gRPC PatientLookup, 2s deadline ──► not found: 404                │
    │                                          ──► timeout/down: business    │
    │                                              error, not a 500          │
    │                                                                        │
    │  3. BookingValidationClient.requireDoctorExists(doctorId)              │
    │       gRPC DoctorLookup, 2s deadline ──► same handling as above         │
    │                                                                        │
    │  4. findConflicting(doctorId, scheduledStart)                          │
    │       PESSIMISTIC_WRITE lock — fast-fail with a clean 409               │
    │       (the DB's unique constraint on (doctor_id, scheduled_start)      │
    │        is the actual source of truth; this lock is a best-effort        │
    │        fast path in front of it, not a replacement for it)              │
    │                                                                        │
    │  5. new Appointment(...) — status = SCHEDULED                          │
    │     save() + publish AppointmentCreatedEvent (same transaction)        │
    └──────────────────┬─────────────────────────────────────────────────────┘
                       │
          ┌─────────────┼──────────────┬───────────────────────────┐
          ▼             ▼              ▼                           ▼
    ┌───────────┐ ┌───────────┐ ┌──────────────┐        ┌────────────────────┐
    │  patient- │ │  doctor-  │ │ Appointment  │        │  AppointmentEvent  │
    │  service  │ │  service  │ │ Repository   │        │  Publisher          │
    │  (gRPC)   │ │  (gRPC)   │ │ (JPA)        │        │  (outbox)           │
    └───────────┘ └───────────┘ └──────┬───────┘        └──────────┬──────────┘
                                       ▼                           ▼
                              appointment_db.appointments   appointment_db.outbox_events
                              unique(doctor_id,                    │
                                     scheduled_start)               ▼
                              @Version                    OutboxRelay ──► Kafka:
                                                            appointment.events


══════════════════════════════════════════════════════════════════════════════════════════════


    ┌────────────────────────────────────────────────────────────────────────────────────┐
    │                    2. CANCEL / RESCHEDULE — STATE TRANSITIONS                      │
    └────────────────────────────────────────────────────────────────────────────────────┘


    POST /{id}/cancel                                POST /{id}/reschedule
          │                                                 │
          ▼                                                 ▼
    Appointment.cancel(reason)                       findConflicting(doctorId, newStart)
      COMPLETED ──► throws                              excluding this appointment's own id
      (INVALID_STATE_TRANSITION)                              │
      anything else ──► CANCELLED                     Appointment.reschedule(newStart, newEnd)
          │                                              CANCELLED/COMPLETED ──► throws
          ▼                                              anything else ──► status reset to
    AppointmentEventPublisher                                              SCHEDULED
      .publishCancelled(...)                                  │
          │                                                    ▼
          ▼                                          ⚠️ NO event published — see Advanced below
    outbox ──► Kafka: appointment.events


══════════════════════════════════════════════════════════════════════════════════════════════


    ┌────────────────────────────────────────────────────────────────────────────────────┐
    │              3. REMINDER JOB — THE ONE PATH THAT SKIPS THE OUTBOX                  │
    └────────────────────────────────────────────────────────────────────────────────────┘


              ┌────────────────────────────────────────┐
              │  @Scheduled(cron = "0 0 * * * *")      │
              │  top of every hour                     │
              └──────────────────┬─────────────────────┘
                                 │
                                 ▼
                    ┌─────────────────────────┐
                    │  AppointmentReminderJob │
                    └────────────┬────────────┘
                                 │
                                 ▼
        findAllByStatusAndScheduledStartBetween(
            CONFIRMED, now+23h, now+25h)          ── a 2-hour window, checked every 1 hour
                                 │
                                 ▼
        for each appointment in that window:
                                 │
                                 ▼
        NotificationRequestedEvent(patientId, "EMAIL",
            "APPOINTMENT_REMINDER", {appointmentId, scheduledStart})
                                 │
                                 ▼
        kafkaTemplate.send(Topics.NOTIFICATION_REQUESTS, ...)
              ⚠️ DIRECT KafkaTemplate call — NOT the outbox — see Advanced below
                                 │
                                 ▼
                    Kafka: notification.requests
                                 │
                                 ▼
                    notification-service (see its own README)
```

---

# 📦 Package Structure

```text
appointment-service/
└── src/main/java/.../appointment/
    │
    ├── web/
    │   ├── AppointmentController
    │   └── dto/
    │       ├── BookAppointmentRequest
    │       ├── AppointmentResponse
    │       ├── CancelRequest
    │       └── RescheduleRequest
    │
    ├── service/
    │   └── AppointmentService
    │
    ├── grpc/
    │   └── BookingValidationClient   — gRPC client to patient-service PatientLookup
    │                                    and doctor-service DoctorLookup
    │
    ├── event/
    │   └── AppointmentEventPublisher
    │
    ├── scheduler/
    │   └── AppointmentReminderJob
    │
    ├── repository/
    │   └── AppointmentRepository
    │
    └── domain/
        ├── Appointment
        ├── AppointmentStatus     (SCHEDULED, CONFIRMED, CANCELLED, COMPLETED, NO_SHOW)
        └── WaitingListEntry
```

---

# 🔐 Double-Booking: Two Layers, One Source of Truth

```text
Client A ──┐
           ├──► findConflicting(doctorId, start)  PESSIMISTIC_WRITE lock
Client B ──┘         │
                      ▼
              serializes concurrent attempts for the SAME doctor+slot
                      │
                      ▼
              second caller sees the first's row ──► BusinessException("SLOT_TAKEN")
                      │
                      ▼
              (fast, clean 409 — the common case)


Database — the actual guarantee, independent of the lock above:

    UNIQUE(doctor_id, scheduled_start)   -- V1 migration

    If the pessimistic lock is ever bypassed or races anyway (a second
    application instance, a direct DB write, a bug), the constraint is
    what actually prevents two rows for the same doctor+slot — the lock
    is a UX nicety in front of it, not a substitute for it.
```

---

# 🧩 The Choreographed Saga Booking Kicks Off

`book()` is the entry point to a workflow that spans three services, coordinated entirely
through events rather than a central orchestrator:

```text
1. appointment-service creates the Appointment (SCHEDULED)
   and publishes AppointmentCreatedEvent
              │
   ┌───────────┴───────────┐
   ▼                       ▼
2. notification-service   3. billing-service
   consumes it, sends a      consumes it, generates
   confirmation (best-       a pending invoice
   effort, not compensated)  for the visit
```

This is **choreographed** (each service reacts independently to events) rather than
**orchestrated** (a central saga coordinator) — appropriate while the compensation logic stays
simple and linear. A dedicated orchestrator earns its complexity once a third or fourth
compensating branch shows up (e.g. an EMR pre-authorization check). If billing fails
irrecoverably today (invalid insurance, say), there's no automatic compensation wired up to
cancel the appointment and free the slot — see
[`../../PROGRESS.md`](../../PROGRESS.md#production-roadmap) ("Orchestrated saga compensation").

---

# ⚠️ Advanced: Two Real Gaps in the Current Code

**Rescheduling publishes no event at all.** `AppointmentEventPublisher` has exactly two
methods — `publishCreated` and `publishCancelled` — and `AppointmentService.reschedule()`
doesn't call either one. The appointment's `scheduledStart`/`scheduledEnd` change and its
status resets to `SCHEDULED` in the database, but nothing downstream (billing, audit,
analytics, the reminder job) is told a reschedule happened. `audit-service`'s trail for a
rescheduled appointment will show its creation and, if it's later cancelled, its
cancellation — with no record of the reschedule in between. The reminder job's own query
(`findAllByStatusAndScheduledStartBetween`) *will* still pick up the appointment's new time
correctly on its next hourly pass, since it reads live appointment state, not the event
stream — so reminders stay accurate even though the audit trail has a gap.

**The reminder job bypasses the outbox entirely — the only producer in this service that
does.** `AppointmentEventPublisher` (used for `book()`/`cancel()`) routes through
`OutboxWriter`, so those events commit atomically with the database write, immune to a
mid-flight crash. `AppointmentReminderJob` instead calls `KafkaTemplate.send(...)` directly,
inside a `@Transactional(readOnly = true)` method — there's no outbox row for a reminder, so a
Kafka outage at exactly the wrong moment can silently drop a reminder for that hour's run. It
isn't unrecoverable, though: since the query has no "already reminded" flag, an appointment
that missed its reminder on one run is picked up again on the next.

Which surfaces the actual duplicate-risk case: the query window is 2 hours
(`now+23h` to `now+25h`) but the job runs every 1 hour, so **consecutive runs' windows
overlap by an hour**. An appointment whose `scheduledStart` falls in that overlap is matched by
two separate hourly runs — nothing on `Appointment` or in the query marks "reminder already
sent" for this window, so that appointment gets a second `NotificationRequestedEvent`, and a
patient could receive the same reminder twice. This is the appointment-side instance of the
at-least-once-delivery pattern documented across the platform's Supporting Services — see
[`../../SUPPORTING_SERVICES.md`](../../SUPPORTING_SERVICES.md) — except here the duplication
risk originates in the *producer*, not a Kafka redelivery on the consumer side.

---

# 🗓️ WaitingListEntry: Modeled, Not Wired Up

`WaitingListEntry` (patient, doctor, `createdAt` — FIFO by creation time, per its own class
comment) has no repository, no controller, and nothing in `AppointmentService` references it.
The entity exists; nothing creates, queries, or notifies against it. Same shape as
`doctor-service`'s `AvailabilitySlot`: a data model in place ahead of the feature logic that
would use it.

---

# 🚀 Where This Is Headed

Circuit breaking extends past billing's payer-eligibility call to this service's own gRPC
calls to patient-service and doctor-service, so a slow or down dependency degrades gracefully
under sustained failure instead of relying solely on the 2-second per-call deadline. The
reminder job's timing and channel selection become configurable per patient rather than a
fixed ~24h/email-first default, and the reschedule-event gap above is the natural next fix —
publishing an `AppointmentRescheduledEvent` the same way `book()`/`cancel()` already do.

---

See [`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) for the full booking workflow diagram, and
[`../../RELIABILITY.md`](../../RELIABILITY.md) for the outbox and rate-limiting details.
