# ADR-003: Choreographed saga for appointment booking

## Status
Accepted

## Context
Booking an appointment has knock-on effects in other bounded contexts: a confirmation
notification should go out, and a pending invoice should be generated. These need to happen
reliably but don't require a single all-or-nothing transaction across three services.

## Decision
Use a CHOREOGRAPHED saga: appointment-service publishes `AppointmentCreatedEvent`;
notification-service and billing-service each independently subscribe and react. There is
no central orchestrator.

## Consequences
+ Low coupling: appointment-service doesn't know or care who's listening.
+ Easy to add a new participant (e.g. a loyalty-points service) without touching existing code.
- Debugging the full flow requires following correlation IDs across services (see
  `CorrelationIdFilter` in the common module) rather than reading one orchestrator's code.
- If the number of compensating actions grows past a handful, an ORCHESTRATED saga (a
  dedicated coordinator service) would likely be worth the added complexity — not the case
  yet here.
