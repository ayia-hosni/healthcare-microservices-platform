# 💳 billing-service

> **Invoices, payments, and payer eligibility checks for the Healthcare Platform.**

**Port:** `8086`
**Database:** `billing_db`
**Database role:** `billing_user`
**Domain events:** consumes `appointment.events`; publishes `billing.events`
**Consumer group:** `billing-service`

The one service that's both a Kafka consumer *and* a Kafka producer of platform domain
events — it reacts to `AppointmentCreatedEvent` by generating an invoice, and produces its own
`billing.events` in turn. Also the service with the platform's only external, non-Kafka
integration: a SOAP call to a payer's eligibility system.

---

## ✨ Capabilities

| Capability                    | Description                                                                |
| --------------------------------| ------------------------------------------------------------------------ |
| 🔄 Choreographed saga participant| Reacts to `AppointmentCreatedEvent`, generates a pending invoice          |
| 🔁 Idempotent consumption         | Checks for an existing invoice before creating one — safe against Kafka redelivery |
| 💰 Overpayment-safe payments      | Rejects a payment that would push the total above the invoice amount      |
| 🛡️ Circuit-breaker-guarded SOAP   | An external payer outage degrades one endpoint, not the whole service     |
| 📞 On-demand eligibility only     | Never on the automatic invoice-generation path — see Advanced below       |
| 🗓️ Scheduled overdue marking       | A daily job flags PENDING invoices past due as OVERDUE                    |
| 📤 Outbox-backed events           | `InvoiceGeneratedEvent`/`PaymentCompletedEvent` commit atomically          |

---

## 🎯 What It Does

```http
GET    /api/v1/invoices/{id}                    hasAnyRole('ADMIN','BILLING_CLERK','PATIENT')
POST   /api/v1/invoices/{id}/payments            hasAnyRole('ADMIN','BILLING_CLERK','PATIENT')
POST   /api/v1/billing/eligibility               hasAnyRole('ADMIN','BILLING_CLERK','PATIENT')
```

There's no `POST /invoices` — invoices are never created directly through the REST API. The
only way one comes into existence is `AppointmentEventConsumer` reacting to
`AppointmentCreatedEvent` on Kafka.

### Standard visit fee

```java
private static final BigDecimal STANDARD_VISIT_FEE = new BigDecimal("150.00");
```

Every invoice generated from an appointment is a flat `$150.00` due in 30 days — there's no
per-doctor, per-specialty, or per-visit-type pricing yet.

---

# 🏗️ Architecture

Two independent inbound paths (Kafka-driven invoice generation, REST-driven payment and
eligibility), plus one gap between them worth understanding clearly.

```text
══════════════════════════════════════════════════════════════════════════════════════════════
                                   BILLING SERVICE
══════════════════════════════════════════════════════════════════════════════════════════════


    ┌────────────────────────────────────────────────────────────────────────────────────┐
    │            1. KAFKA — INVOICE GENERATION (SAGA PARTICIPANT)                        │
    └────────────────────────────────────────────────────────────────────────────────────┘


    Kafka: appointment.events
              │
              ▼
    ┌──────────────────────────────────────────────────────┐
    │             AppointmentEventConsumer                  │
    │             @KafkaListener, groupId=billing-service   │
    └──────────────────┬─────────────────────────────────────┘
                       │
                       ▼
              eventType == "AppointmentCreatedEvent" ?
                       │
              no ──► return (ignore cancellations/other types on this topic)
                       │ yes
                       ▼
        invoiceRepository.findAllByAppointmentId(appointmentId).isEmpty() ?
                       │
              no ──► return (already processed — idempotent against redelivery)
                       │ yes
                       ▼
    ┌──────────────────────────────────────────────────────┐
    │  new Invoice(patientId, appointmentId,                │
    │              STANDARD_VISIT_FEE = $150.00,             │
    │              dueDate = now + 30 days)                  │
    │  status = PENDING                                       │
    │  save()                                                  │
    └──────────────────┬─────────────────────────────────────┘
                       │
                       ▼
          BillingEventPublisher.publishInvoiceGenerated(...)
                       │
                       ▼
          outbox ──► OutboxRelay ──► Kafka: billing.events

    On failure anywhere in this handler: rethrown (not swallowed) so
    KafkaDlqConfig's error handler retries, then dead-letters —
    swallowing here would let the surrounding @Transactional commit
    partial state, since Spring only rolls back on a propagated exception.


══════════════════════════════════════════════════════════════════════════════════════════════


    ┌────────────────────────────────────────────────────────────────────────────────────┐
    │                     2. REST — PAYMENTS (OVERPAYMENT-SAFE)                          │
    └────────────────────────────────────────────────────────────────────────────────────┘


        Client
          │
          │ POST /{id}/payments  { amount, paymentMethod }
          ▼
    ┌─────────────────────────────────────┐
    │           InvoiceController            │
    └──────────────────┬──────────────────┘
                       │
                       ▼
    ┌──────────────────────────────────────────────────────────┐
    │                  InvoiceService.pay(id, req)                │
    │                                                             │
    │  alreadyPaid = sum of invoice.payments                       │
    │  totalAfterThis = alreadyPaid + req.amount                   │
    │                                                             │
    │  totalAfterThis > invoice.amount ?                            │
    │       yes ──► BusinessException("OVERPAYMENT")                │
    │                                                             │
    │  new Payment(amount, method), invoice.addPayment(payment)     │
    │  totalAfterThis == invoice.amount ?                            │
    │       yes ──► invoice.markPaid()                              │
    │                                                             │
    │  publish PaymentCompletedEvent (outbox)                       │
    └──────────────────┬──────────────────────────────────────────┘
                       │
              ┌─────────┴─────────┐
              ▼                   ▼
    billing_db.invoices    billing_db.payments
    (@Version — optimistic  (cascade child of
     lock)                   Invoice)
                       │
                       ▼
          outbox ──► Kafka: billing.events


══════════════════════════════════════════════════════════════════════════════════════════════


    ┌────────────────────────────────────────────────────────────────────────────────────┐
    │       3. SOAP — PAYER ELIGIBILITY, ON-DEMAND ONLY, CIRCUIT-BREAKER-GUARDED         │
    └────────────────────────────────────────────────────────────────────────────────────┘


        Client
          │
          │ POST /api/v1/billing/eligibility  { memberId, payerId, dateOfBirth }
          │
          │ ⚠️ NEVER called automatically by AppointmentEventConsumer above —
          │    a slow/down payer must never be able to block or retry-storm
          │    invoice generation (see EligibilityController's own comment)
          ▼
    ┌─────────────────────────────────────┐
    │          EligibilityController        │
    └──────────────────┬──────────────────┘
                       │
                       ▼
    ┌──────────────────────────────────────────────────────────┐
    │            EligibilityService.checkEligibility(req)         │
    │                                                             │
    │  @CircuitBreaker(name="payerEligibility",                    │
    │                   fallbackMethod="eligibilityUnavailable")   │
    │                                                             │
    │  build CheckEligibilityRequest (JAXB/XML)                     │
    └──────────────────┬──────────────────────────────────────────┘
                       │
              circuit CLOSED                circuit OPEN
                       │                            │
                       ▼                            ▼
    ┌───────────────────────────────┐   eligibilityUnavailable(req, e)
    │      PayerEligibilityClient    │      ──► BusinessException
    │      (Spring-WS gateway)       │          ("ELIGIBILITY_UNAVAILABLE")
    │                                │          — not a 500
    │  connect timeout: 2s           │
    │  read timeout: 3s              │
    │  (PayerClientConfig)           │
    └──────────────┬─────────────────┘
                   ▼
        External payer (SOAP) — payer-mock (WireMock) locally,
        see docs/adr/0003-soap-payer-eligibility-integration.md
                   │
                   ▼
        CheckEligibilityResponse ──► EligibilityResponse
        (eligible, planName, copayAmount, payerMessage)


══════════════════════════════════════════════════════════════════════════════════════════════


    ┌────────────────────────────────────────────────────────────────────────────────────┐
    │              4. SCHEDULED — OVERDUE MARKING (ONE-WAY, NOT COMPENSATED)             │
    └────────────────────────────────────────────────────────────────────────────────────┘


              ┌────────────────────────────────────────┐
              │  @Scheduled(cron = "0 0 2 * * *")      │
              │  daily at 02:00                        │
              └──────────────────┬─────────────────────┘
                                 │
                                 ▼
                    ┌─────────────────────────┐
                    │    OverdueInvoiceJob    │
                    └────────────┬────────────┘
                                 │
                                 ▼
        findAllByStatusAndDueDateBefore(PENDING, now)
                                 │
                                 ▼
                    invoice.markOverdue()  for each
                                 │
                                 ▼
                       billing_db.invoices
                       status: PENDING ──► OVERDUE

              ⚠️ Does NOT publish an event and does NOT reach into
                 appointment-service to cancel anything — per this
                 job's own class comment, that's deliberate: no
                 service ever mutates another service's tables. The
                 "publish an event appointment-service consumes to
                 auto-cancel" half of that design isn't built yet.
```

---

# 📦 Package Structure

```text
billing-service/
└── src/main/java/.../billing/
    │
    ├── web/
    │   ├── InvoiceController
    │   ├── EligibilityController
    │   └── dto/
    │       ├── InvoiceResponse / PaymentRequest
    │       └── EligibilityRequest / EligibilityResponse
    │
    ├── service/
    │   └── InvoiceService
    │
    ├── event/
    │   ├── AppointmentEventConsumer   — consumes appointment.events
    │   └── BillingEventPublisher      — produces billing.events
    │
    ├── payer/
    │   ├── EligibilityService         — circuit breaker lives here
    │   ├── PayerEligibilityClient     — pure SOAP transport
    │   └── PayerClientConfig          — JAXB marshaller + timeouts
    │
    ├── scheduler/
    │   └── OverdueInvoiceJob
    │
    ├── config/
    │   └── KafkaDlqConfig
    │
    ├── repository/
    │   └── InvoiceRepository
    │
    └── domain/
        ├── Invoice        (aggregate root — PENDING, PAID, OVERDUE, CANCELLED)
        ├── Payment         (cascade child of Invoice)
        └── InsuranceClaim  — built; no repository, no caller anywhere (see below)
```

---

# ⚠️ Advanced: Why Eligibility Is Never Automatic

`EligibilityController`'s own class comment states the design decision outright: the
eligibility check is **deliberately not wired into** `AppointmentEventConsumer`'s Kafka-driven
invoice generation. If it were, a slow or unreachable external payer could block or
retry-storm the Kafka consumer that generates every invoice on the platform — one external
dependency's outage would then cascade into billing being unable to process *any* appointment,
not just ones needing an eligibility check. Keeping it a separate, on-demand `POST` endpoint
means a payer outage degrades exactly one endpoint (`/eligibility`), never the automatic
invoice pipeline. See
[ADR-0003](../../docs/adr/0003-soap-payer-eligibility-integration.md) for the full reasoning.

---

# ⚠️ Advanced: OverdueInvoiceJob's Missing Compensation Step

`OverdueInvoiceJob` flags overdue invoices, full stop — it doesn't reach into
`appointment-service`'s tables to cancel anything (correctly: no service on this platform ever
mutates another service's data directly), but the *other* half of that design — publishing an
event `appointment-service` could consume to auto-cancel an unpaid appointment — isn't built
either. The job's own class comment says as much: "in the full design it would publish an
event." Today, an invoice going `OVERDUE` is entirely invisible outside `billing_db` — no Kafka
event, no notification, nothing for another service to react to.

---

# 🗄️ InsuranceClaim: Modeled, Not Wired Up

`InsuranceClaim` (`invoiceId`, `insuranceProvider`, a `SUBMITTED`/`APPROVED`/`DENIED` status,
`approve()`/`deny()` transitions) is a complete entity with real state-transition methods —
and has **no repository at all**, let alone a service or controller. It's not reachable from
any code path in this service. Same pattern as `doctor-service`'s `AvailabilitySlot`,
`appointment-service`'s `WaitingListEntry`, and `emr-service`'s `Allergy`/`PatientServiceClient`
— a data model built ahead of the feature that would use it. See
[`../../docs/architecture/supporting-services.md`](../../docs/architecture/supporting-services.md) and each of those services' own
READMEs for the platform-wide pattern.

---

# 🚀 Where This Is Headed

The circuit-breaker pattern already proven on the payer eligibility call is the template for
billing-service's other outbound dependencies as they're added. Eligibility responses start
getting cached, so a payer outage doesn't stall every new eligibility check that happens to
need the same lookup within the cache window. `OverdueInvoiceJob` gaining its missing
compensating event, and `InsuranceClaim` gaining a repository and a caller, are the two most
direct "finish what's already modeled" items here.

---

See [`../../docs/reliability/README.md`](../../docs/reliability/README.md) for how the circuit breaker and outbox work
across the platform, and [`../../docs/architecture/README.md`](../../docs/architecture/README.md) for the full
choreographed saga this service participates in.
