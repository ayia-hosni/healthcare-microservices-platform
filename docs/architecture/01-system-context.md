# System Context

The **System Context** is the C4 Level 1 view of the Healthcare Microservices Platform. It defines the system boundary, the people who interact with the platform, and the external systems that communicate with it.

This document intentionally stays at the **system level**. Internal microservices, databases, messaging infrastructure, and deployment architecture are documented separately.

---

## Table of Contents

1. [System Context Diagram](#1-system-context-diagram)
2. [External Actors](#2-external-actors)

    * [2.1 Patient](#21-patient)
    * [2.2 Doctor / Clinical Staff](#22-doctor--clinical-staff)
    * [2.3 Administrator](#23-administrator)
3. [User Access Boundary](#3-user-access-boundary)
4. [External Payer / Clearinghouse](#4-external-payer--clearinghouse)

    * [4.1 Eligibility Verification](#41-eligibility-verification)
    * [4.2 Why Eligibility Is Synchronous](#42-why-eligibility-is-synchronous)
    * [4.3 Development Environment](#43-development-environment)
5. [Notification Boundary](#5-notification-boundary)
6. [External Integration Overview](#6-external-integration-overview)
7. [What's Deliberately Out of Scope](#7-whats-deliberately-out-of-scope)
8. [Integration Boundaries](#8-integration-boundaries)
9. [Architectural Principles](#9-architectural-principles)
10. [Related Architecture Documentation](#10-related-architecture-documentation)

---

## 1. System Context Diagram

```text
╔══════════════════════════════════════════════════════════════════════════════╗
║                    HEALTHCARE MICROSERVICES PLATFORM                         ║
║                                                                              ║
║     Cloud-native healthcare platform for patient, clinical,                 ║
║     appointment, EMR, billing, notification, audit & analytics workflows    ║
╚══════════════════════════════════════════════════════════════════════════════╝
          ▲                         ▲                         ▲
          │                         │                         │
          │                         │                         │
┌───────────────────┐    ┌──────────────────────┐    ┌──────────────────────┐
│     PATIENT       │    │ DOCTOR / CLINICAL    │    │   ADMINISTRATOR      │
│                   │    │       STAFF          │    │                      │
├───────────────────┤    ├──────────────────────┤    ├──────────────────────┤
│ • Register/Login  │    │ • Manage availability│    │ • Manage users       │
│ • Book appointment│    │ • Manage appointments│    │ • Manage roles/RBAC  │
│ • Cancel/reschedule│   │ • View patients      │    │ • Review audit logs  │
│ • View own records│    │ • View clinical data │    │ • View analytics     │
│ • View appointments│   │ • Manage encounters  │    │ • Platform operations│
└───────────────────┘    │ • Prescriptions      │    └──────────────────────┘
                         └──────────────────────┘

                               USER ACCESS
                                    │
                                    ▼
                         ┌──────────────────────┐
                         │     FRONTEND SPA     │
                         └──────────┬───────────┘
                                    │
                              HTTP / HTTPS
                                    │
                                    ▼
                         ┌──────────────────────┐
                         │    NGINX INGRESS     │
                         └──────────┬───────────┘
                                    │
                                    ▼
╔══════════════════════════════════════════════════════════════════════════════╗
║                    HEALTHCARE MICROSERVICES PLATFORM                         ║
╚══════════════════════════════════════════════════════════════════════════════╝
                                    │
                  ┌─────────────────┴─────────────────┐
                  │                                   │
                  ▼                                   ▼
     ┌────────────────────────┐          ┌────────────────────────┐
     │ EXTERNAL PAYER /        │          │ NOTIFICATION CHANNELS  │
     │ CLEARINGHOUSE           │          │                        │
     ├────────────────────────┤          ├────────────────────────┤
     │ SOAP eligibility       │          │ Email                  │
     │ verification            │          │ SMS                    │
     │                         │          │ Push                   │
     │ WireMock in dev         │          │                        │
     └────────────────────────┘          │ Currently simulated    │
                                         └────────────────────────┘
```

---

## 2. External Actors

### 2.1 Patient

Patients interact with the platform through the frontend to:

* Register and authenticate.
* View personal information.
* Book appointments.
* Cancel and reschedule appointments.
* View appointments.
* Access permitted clinical records.

Patients can only access resources authorized for their account.

### 2.2 Doctor / Clinical Staff

Doctors and authorized clinical staff use the platform to:

* Manage availability.
* Manage appointments.
* View assigned patients.
* Access clinical records.
* Work with encounters.
* Manage prescriptions and other EMR workflows.

Clinical access is controlled through the platform's authorization and RBAC model.

### 2.3 Administrator

Administrators operate the platform and can:

* Manage users and roles.
* Perform administrative operations.
* Review audit trails.
* Access analytics and reporting.
* Manage platform-level configuration.

Administrative access is governed by the roles managed by `identity-service`.

---

## 3. User Access Boundary

All human users follow the same high-level access path:

```text
┌───────────────────┐
│     PATIENT       │
└─────────┬─────────┘
          │
┌─────────▼─────────┐
│ DOCTOR / CLINICAL │
│       STAFF       │
└─────────┬─────────┘
          │
┌─────────▼─────────┐
│   ADMINISTRATOR   │
└─────────┬─────────┘
          │
          ▼
┌─────────────────────────┐
│      FRONTEND SPA       │
└────────────┬────────────┘
             │
             │ HTTP / HTTPS
             ▼
┌─────────────────────────┐
│      NGINX INGRESS      │
└────────────┬────────────┘
             │
             ▼
╔════════════════════════════════════╗
║ HEALTHCARE MICROSERVICES PLATFORM ║
╚════════════════════════════════════╝
```

Users do not communicate directly with individual backend microservices.

The frontend communicates with the platform through the NGINX ingress boundary.

See [Communication Architecture](04-communication-architecture.md) for the detailed communication model.

---

## 4. External Payer / Clearinghouse

The payer/clearinghouse is the platform's primary external business integration.

### 4.1 Eligibility Verification

`billing-service` exposes:

```text
POST /api/v1/billing/eligibility
```

The integration follows a synchronous REST-to-SOAP flow:

```text
┌─────────────────────┐
│ Authorized Client   │
└──────────┬──────────┘
           │
           │ REST
           ▼
┌─────────────────────┐
│   billing-service   │
└──────────┬──────────┘
           │
           │ SOAP
           ▼
┌─────────────────────────────┐
│ External Payer /            │
│ Clearinghouse               │
│                             │
│ Insurance Eligibility       │
└──────────┬──────────────────┘
           │
           │ Eligibility Result
           ▼
┌─────────────────────┐
│   billing-service   │
└──────────┬──────────┘
           │
           │ REST Response
           ▼
┌─────────────────────┐
│ Authorized Client   │
└─────────────────────┘
```

The integration provides **insurance eligibility verification only**.

It does not currently implement:

* Claims submission.
* Claims adjudication.
* Payment processing.
* Remittance / ERA processing.

### 4.2 Why Eligibility Is Synchronous

Eligibility verification requires an immediate response.

The payer integration is therefore intentionally separated from the Kafka-driven invoice workflow:

```text
                  INVOICE WORKFLOW
                        │
                        ▼
                  Kafka Events
                        │
                        ▼
              ┌───────────────────┐
              │ Billing Consumers │
              └───────────────────┘


                 ELIGIBILITY
                    WORKFLOW
                        │
                        ▼
              ┌───────────────────┐
              │ billing-service   │
              └─────────┬─────────┘
                        │
                        │ SOAP
                        ▼
              ┌───────────────────┐
              │ External Payer    │
              └───────────────────┘
```

This prevents a slow or unavailable payer from blocking Kafka consumers or causing uncontrolled retry storms.

See [ADR-0003 — SOAP Payer Eligibility Integration](../adr/0003-soap-payer-eligibility-integration.md).

### 4.3 Development Environment

In local and development environments, the payer is represented by a **WireMock stub** rather than a real insurance trading partner.

This allows SOAP contracts and failure scenarios to be exercised without requiring access to a production payer.

---

## 5. Notification Boundary

`notification-service` owns outbound notification processing.

```text
                         PLATFORM EVENT
                               │
                               ▼
                      ┌─────────────────┐
                      │    RabbitMQ     │
                      └────────┬────────┘
                               │
                               ▼
                   ┌──────────────────────┐
                   │ notification-service │
                   └──────────┬───────────┘
                              │
                    ┌─────────┼─────────┐
                    │         │         │
                    ▼         ▼         ▼
              ┌─────────┐ ┌───────┐ ┌─────────┐
              │  Email  │ │  SMS  │ │  Push   │
              │ Handler │ │Handler│ │ Handler │
              └────┬────┘ └───┬───┘ └────┬────┘
                   │           │          │
                   └───────────┼──────────┘
                               ▼
                    ┌──────────────────────┐
                    │ External Provider    │
                    │                      │
                    │ SES / Twilio / FCM   │
                    └──────────┬───────────┘
                               │
                               │ Current implementation
                               ▼
                    ┌──────────────────────┐
                    │ Simulated / Logged   │
                    └──────────────────────┘
```

The service provides the architectural boundary for:

* Email.
* SMS.
* Push.
* Asynchronous processing.
* Retries.
* Dead-letter handling.
* Channel-specific handlers.

Actual delivery is currently **simulated and logged** rather than sent through a production provider.

---

## 6. External Integration Overview

```text
                         HEALTHCARE PLATFORM
                                │
                 ┌──────────────┴──────────────┐
                 │                             │
                 ▼                             ▼
        ┌─────────────────┐          ┌──────────────────────┐
        │ billing-service │          │ notification-service │
        └────────┬────────┘          └──────────┬───────────┘
                 │                              │
                 │ SOAP                         │ RabbitMQ
                 │                              │
                 ▼                              ▼
        ┌─────────────────┐          ┌──────────────────────┐
        │ External Payer  │          │ Notification Handlers │
        │ / Clearinghouse │          └──────────┬───────────┘
        └─────────────────┘                     │
                                      ┌─────────┼─────────┐
                                      ▼         ▼         ▼
                                    Email      SMS       Push
                                      │         │         │
                                      └─────────┼─────────┘
                                                ▼
                                      External Provider
                                       / Simulation
```

---

## 7. What's Deliberately Out of Scope

```text
┌─────────────────────────────────────────────────────────────┐
│                    NOT CURRENTLY INTEGRATED                  │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ❌ Insurance Claims / Adjudication                         │
│                                                             │
│  ❌ Payment / Remittance / ERA                              │
│                                                             │
│  ❌ External Hospital / EHR Systems                         │
│                                                             │
│  ❌ HL7 / FHIR Interoperability Exchange                    │
│                                                             │
│  ❌ Third-Party Identity Provider                            │
│                                                             │
│  ❌ Production Email / SMS / Push Provider                  │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

These capabilities are future integration boundaries rather than current external dependencies.

---

## 8. Integration Boundaries

```text
                    ┌──────────────────────────────┐
                    │ HEALTHCARE PLATFORM          │
                    └──────────────┬───────────────┘
                                   │
             ┌─────────────────────┼─────────────────────┐
             │                     │                     │
             ▼                     ▼                     ▼
      ┌─────────────┐       ┌──────────────┐      ┌─────────────┐
      │   Billing   │       │ Notification │      │  Identity   │
      │  Boundary   │       │   Boundary   │      │  Boundary   │
      └──────┬──────┘       └──────┬───────┘      └─────────────┘
             │                     │
             │ SOAP                │ RabbitMQ
             ▼                     ▼
      ┌─────────────┐       ┌──────────────┐
      │ Payer /     │       │ Email / SMS  │
      │ Clearing-   │       │ / Push       │
      │ house       │       │ Providers    │
      └─────────────┘       └──────────────┘
```

| Boundary                       | Owner                  | Communication          | Purpose                                     |
| ------------------------------ | ---------------------- | ---------------------- | ------------------------------------------- |
| Insurance eligibility          | `billing-service`      | REST → SOAP            | Synchronous insurance coverage verification |
| Notifications                  | `notification-service` | RabbitMQ → handlers    | Asynchronous notification processing        |
| Authentication & authorization | `identity-service`     | Internal platform APIs | Platform-owned identity and RBAC            |

---

## 9. Architectural Principles

### 9.1 Clear System Boundary

The C4 Level 1 view treats the Healthcare Microservices Platform as one system. Internal implementation details are intentionally hidden.

### 9.2 Controlled User Access

All users enter through the frontend and NGINX ingress rather than directly accessing backend services.

### 9.3 Capability-Owned Integrations

Each external integration is owned by the service responsible for the corresponding business capability.

### 9.4 Synchronous Where Immediate Results Matter

Insurance eligibility is synchronous because the caller requires an immediate result.

### 9.5 Asynchronous Background Processing

Notification processing uses RabbitMQ so delivery work can be handled independently from the originating business operation.

### 9.6 Failure Isolation

External dependencies are treated as failure boundaries. A payer outage should not become an outage for unrelated platform workflows.

---

## 10. Related Architecture Documentation

| Document                                                       | Focus                                               |
| -------------------------------------------------------------- | --------------------------------------------------- |
| [System Context](01-system-context.md)                         | Users, system boundary, and external integrations   |
| [Container Architecture](02-container-architecture.md)         | Internal microservices and application containers   |
| [Data Architecture](03-data-architecture.md)                   | Databases, persistence, caching, and data ownership |
| [Communication Architecture](04-communication-architecture.md) | REST, Kafka, RabbitMQ, and service communication    |
| [ADR-0003](../adr/0003-soap-payer-eligibility-integration.md)  | SOAP payer eligibility integration decision         |

> **C4 Level 1:** This document answers **who uses the platform, why they use it, and which external systems interact with it**. Internal services, databases, queues, and infrastructure are intentionally covered by lower-level architecture documentation.
