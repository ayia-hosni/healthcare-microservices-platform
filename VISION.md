# 🔭 Product Vision — Advanced EHR Platform

> **This document describes a target end-state, not the current system.** Nothing in this file
> is a claim about what's running today. For what's actually implemented, see
> [`README.md`](README.md) (architecture as built) and [`PROGRESS.md`](PROGRESS.md)
> (implemented vs. designed vs. planned, for the infrastructure/reliability layer).

Each section below ends with a **Today** callout mapping the vision back to what currently
exists in this repository, so this stays a useful planning document instead of drifting into
fiction.

---

## Table of Contents

* [1. Site & Application Map](#1-site--application-map)
* [2. Portal Workspaces](#2-portal-workspaces)
* [3. Patient Clinical Record](#3-patient-clinical-record)
* [4. Clinical Workflow](#4-clinical-workflow)
* [5. Advanced Platform Features](#5-advanced-platform-features)
* [6. Analytics & Operations](#6-analytics--operations)
* [7. Platform Services](#7-platform-services)
* [Vision vs. Reality Summary](#vision-vs-reality-summary)

---

## 1. Site & Application Map

```text
╔══════════════════════════════════════════════════════════════════╗
║                    ADVANCED EHR PLATFORM WEBSITE                  ║
╚══════════════════════════════════════════════════════════════════╝
                              │
       ┌──────────────────────┼──────────────────────┐
       ▼                      ▼                       ▼
┌─────────────┐      ┌─────────────────┐      ┌─────────────────┐
│ PUBLIC SITE │      │ AUTHENTICATION  │      │ SUPPORT /       │
│             │      │                 │      │ COMMUNICATION   │
│ Home        │      │ Login / Logout  │      │ Messages        │
│ Features    │      │ MFA             │      │ Alerts          │
│ Demo        │      │ Passkey / SSO   │      │ Notifications   │
│ About       │      │                 │      │ Help            │
│ Contact     │      │                 │      │                 │
└─────────────┘      └─────────────────┘      └─────────────────┘
```

```text
╔══════════════════════════════════════════════════════════════════╗
║                          EHR APPLICATION                          ║
╚══════════════════════════════════════════════════════════════════╝
                              │
       ┌──────────────────────┼──────────────────────┐
       ▼                      ▼                       ▼
┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
│ CLINICAL PORTAL  │  │ PATIENT PORTAL   │  │ ADMIN PORTAL     │
│                  │  │                  │  │                  │
│ Doctors          │  │ Patients         │  │ Hospital Admin   │
│ Nurses           │  │ Guardians        │  │ Super Admin      │
│ Pharmacists      │  │                  │  │ IT / Security    │
│ Laboratory Staff │  │                  │  │                  │
└──────────────────┘  └──────────────────┘  └──────────────────┘
```

**Today:** the frontend is a single Angular SPA with one role-agnostic navigation
(dashboard, patients, doctors, appointments, calendar, billing, profile, settings, support) —
no public marketing site, no portal split, no MFA/SSO/passkey (email+password JWT only).

---

## 2. Portal Workspaces

```text
┌───────────────────────┐   ┌───────────────────────┐   ┌───────────────────────┐
│   DOCTOR WORKSPACE    │   │      MY HEALTH        │   │    ADMINISTRATION     │
├───────────────────────┤   ├───────────────────────┤   ├───────────────────────┤
│ 🏠 Dashboard          │   │ 🏠 Dashboard          │   │ 🏠 Admin Dashboard    │
│ 👥 Patients           │   │ 📅 Appointments       │   │ 👥 Users & Staff      │
│ 📅 Schedule           │   │ ❤️ Medical History    │   │ 🏥 Hospitals/Clinics  │
│ 🏥 Clinical           │   │ 💊 Medications        │   │ 🏢 Departments        │
│ 🧪 Laboratory         │   │ 🧪 Lab Results        │   │ 🛏 Beds & Resources   │
│ 💊 Pharmacy           │   │ 🩻 Imaging            │   │ 🔐 Roles & Perms      │
│ 🔔 Clinical Alerts    │   │ 📄 Documents          │   │ 📊 Analytics          │
│ 💬 Secure Messages    │   │ 💬 Messages           │   │ 🔌 Integrations       │
│ 📊 Clinical Analytics │   │ 🔐 Privacy & Consent  │   │ 📜 Audit & Security   │
│ ✨ AI Copilot         │   │ 💳 Billing            │   │ ⚙️ System Settings    │
└───────────────────────┘   └───────────────────────┘   └───────────────────────┘
```

**Today:** appointment scheduling, encounters/diagnoses/meds/allergies/lab-results/documents
(emr-service), billing (billing-service), and messaging via async notifications
(notification-service) all exist as backend APIs. What's missing end-to-end: dedicated
imaging, a consent-management model, an admin console for hospitals/departments/beds, and any
AI Copilot feature.

---

## 3. Patient Clinical Record

```text
╔══════════════════════════════════════════════════════════════════╗
║                     PATIENT CLINICAL RECORD                       ║
╚══════════════════════════════════════════════════════════════════╝
                              │
       ┌──────────────────────┼──────────────────────┐
       ▼                      ▼                       ▼
┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
│ PATIENT PROFILE  │  │ CLINICAL HISTORY │  │ PATIENT TIMELINE │
│                  │  │                  │  │                  │
│ Demographics     │  │ Conditions       │  │ Visits           │
│ MRN / ID         │  │ Allergies        │  │ Labs             │
│ Contacts         │  │ Medications      │  │ Prescriptions    │
│ Insurance        │  │ Immunizations    │  │ Imaging          │
│ Risk Level       │  │ Procedures       │  │ Admissions       │
└──────────────────┘  └──────────────────┘  └──────────────────┘
```

**Today:** demographics, MRN (patient UUID), insurance, and contacts are real
(patient-service, now field-level encrypted at rest). Allergies/medications exist
(emr-service). Conditions/diagnoses exist via ICD-10 codes on encounters. Immunizations,
procedures, imaging, admissions, and a computed "risk level" do not exist yet.

---

## 4. Clinical Workflow

```text
   Patient Registration
          │
          ▼
   Appointment Booking ──────────────► Calendar / Schedule
          │
          ▼
   Check-in / Triage
          │
          ▼
   Clinical Encounter
          │
   ┌──────┼───────────────┬─────────────────┬────────────────┐
   ▼      ▼               ▼                 ▼                ▼
 Notes  Diagnosis      Prescription       Lab Order      Imaging Order
   │      │               │                 │                │
   └──────┴───────────────┴──────────┬──────┴────────────────┘
                                      ▼
                          Clinical Decision Support
                                      │
                         ┌────────────┼────────────┐
                         ▼            ▼             ▼
                     Allergy       Drug          Critical
                      Alert     Interaction       Result
                                      │
                                      ▼
                              Doctor Review
                                      │
                                      ▼
                              Sign Encounter
                                      │
                                      ▼
                              Patient Record
                                      │
                                      ▼
                         Patient Portal Updated
```

**Today:** registration, booking, and encounter creation (notes, diagnosis, medications,
lab results as free-form entries) are real and wired end-to-end, publishing Kafka events that
flow into notification/audit/analytics services. Triage, structured lab/imaging *orders*,
clinical decision support (allergy/interaction/critical-result alerting), and an explicit
encounter sign-off step do not exist — an encounter is simply created and immediately visible.

---

## 5. Advanced Platform Features

```text
┌─────────────────────┐   ┌─────────────────────┐   ┌─────────────────────┐
│ ✨ AI CLINICAL       │   │ 🔐 SECURITY &       │   │ 🔄 INTEROPERABILITY │
│    COPILOT          │   │    PRIVACY          │   │                     │
├─────────────────────┤   ├─────────────────────┤   ├─────────────────────┤
│ Patient Summary     │   │ MFA                 │   │ FHIR R4             │
│ Note Generation     │   │ RBAC + ABAC         │   │ HL7 Integration     │
│ Document AI / OCR   │   │ Consent Management  │   │ External Hospitals  │
│ Risk Prediction     │   │ Break Glass Access  │   │ Laboratories        │
│ Clinical Insights   │   │ Immutable Audit     │   │ Insurance           │
│ Human Approval      │   │ Encryption          │   │ Patient APIs        │
└─────────────────────┘   └─────────────────────┘   └─────────────────────┘
```

**Today:** RBAC and append-only audit logging (audit-service) are real. Field-level encryption
at rest (AES-256-GCM) for PHI is real. A single FHIR `DocumentReference` resource type exists
in emr-service. Everything else in this box — the entire AI Copilot column, MFA, ABAC, consent
management, break-glass access, HL7, and external hospital/lab/insurance/patient integrations —
is not built.

---

## 6. Analytics & Operations

```text
         Clinical Analytics              Operational Analytics
                 │                                │
        ┌────────┼────────┐              ┌────────┼────────┐
        ▼        ▼        ▼              ▼        ▼        ▼
     High Risk  Disease  Patient       Wait     Bed      Doctor
     Patients   Trends   Outcomes      Time  Occupancy   Workload
```

**Today:** analytics-service consumes domain events for reporting, but none of the six
specific reports above (risk scoring, disease trends, outcomes, wait time, bed occupancy,
doctor workload) are implemented — there's no bed/occupancy concept in the system at all yet.

---

## 7. Platform Services

```text
        Frontend Applications
                  │
                  ▼
        API Gateway / GraphQL BFF
                  │
   ┌──────────────┼──────────────┬──────────────┬─────────────────┐
   ▼              ▼              ▼              ▼                 ▼
Identity       Patient       Clinical      Appointment       Laboratory
Service        Service       Service       Service            Service
   │              │              │              │                 │
   ▼              ▼              ▼              ▼                 ▼
Pharmacy       Billing       Consent       Notification         Audit
Service        Service       Service        Service            Service
   │              │              │              │                 │
   └──────────────┴──────────────┼──────────────┴─────────────────┘
                                  ▼
                        Kafka / RabbitMQ Events
                                  │
        ┌──────────────────────────┼──────────────────────────┐
        ▼                          ▼                           ▼
   PostgreSQL                    Redis                  Elasticsearch
        │                          │                           │
        └──────────────────────────┼──────────────────────────┘
                                    ▼
                          MinIO / Object Storage
                                    │
                                    ▼
                              FHIR R4 Layer
```

**Today (real 10-service map):** `graphql-gateway` → `identity-service`, `patient-service`,
`doctor-service`, `appointment-service`, `emr-service` (covers "Clinical" + lab results as a
sub-resource, not a standalone service), `billing-service`, `notification-service`,
`audit-service`, `analytics-service` → Kafka + RabbitMQ → PostgreSQL + Redis + MinIO. There is
no `pharmacy-service`, `laboratory-service`, or `consent-service` — those responsibilities
would either need to be split out of `emr-service` or built new. Elasticsearch is provisioned
in `docker-compose.yml` but has zero consuming code today (see `PROGRESS.md`). "FHIR R4 Layer"
as a platform-wide layer doesn't exist — only the one `DocumentReference` resource in
emr-service.

---

## Vision vs. Reality Summary

| Vision item | Status |
| --- | --- |
| Core microservices (identity/patient/doctor/appointment/emr/billing/notification/audit/analytics + GraphQL BFF) | ✅ Built |
| JWT auth + RBAC | ✅ Built |
| PHI field-level encryption at rest | ✅ Built |
| Async messaging (Kafka + RabbitMQ), transactional outbox | ✅ Built |
| MinIO document storage + partial FHIR `DocumentReference` | ✅ Built |
| Elasticsearch | 🏗️ Provisioned, unused |
| Public marketing site (Home/Features/Demo/About/Contact) | ❌ Not started |
| MFA / SSO / Passkey login | ❌ Not started |
| ABAC / Break-glass access | ❌ Not started |
| Consent management | ❌ Not started |
| AI Clinical Copilot (summaries, note generation, OCR, risk prediction) | ❌ Not started |
| HL7 integration, external hospital/lab/insurance interoperability | ❌ Not started |
| "FHIR R4 Layer" as a platform-wide layer (vs. today's single `DocumentReference` resource) | ❌ Not started |
| Laboratory / Pharmacy / Consent as standalone services | ❌ Not started |
| Admin portal (hospitals, departments, beds, roles UI) | ❌ Not started |
| Triage / check-in step | ❌ Not started |
| Imaging, immunizations, procedures, admissions | ❌ Not started |
| Clinical decision support (allergy/interaction/critical-result alerts) | ❌ Not started |
| Encounter sign-off step (encounters are visible immediately, no review gate) | ❌ Not started |
| Clinical & operational analytics (risk scoring, disease trends, bed occupancy, workload) | ❌ Not started |
| Multi-portal frontend split (clinical / patient / admin) | ❌ Not started — one shared Angular SPA today |

Legend: ✅ implemented and running · 🏗️ partially in place (infra exists, not wired up) ·
🚧 in progress · ❌ not started. Infrastructure-level roadmap items (RS256/JWKS, mTLS, HPA,
etc.) are tracked separately in `PROGRESS.md` and are not repeated here — this file is about
product surface area, that one is about production-readiness of what already exists.
