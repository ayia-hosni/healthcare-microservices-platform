# 📋 emr-service

> **Encounters, diagnoses, medications, lab results, allergies, and FHIR-shaped clinical
> document storage for the Healthcare Platform.**

**Port:** `8085`
**Database:** `emr_db`
**Database role:** `emr_user`
**Domain events:** publishes `emr.events`, `notification.requests`

Owns the clinical record itself — as opposed to demographic data (`patient-service`) or
scheduling (`appointment-service`). The one service on the platform that speaks a healthcare
interoperability standard (FHIR `DocumentReference`) at its edge, and the one whose object
storage is a first-class dependency, not an afterthought.

---

## ✨ Capabilities

| Capability                    | Description                                                                |
| --------------------------------| ----------------------------------------------------------------------- |
| 🩺 Encounter-centric aggregate  | Diagnoses, medications, and lab results only exist attached to an encounter |
| 💊 Prescription events           | Adding a medication publishes `PrescriptionCreatedEvent`                  |
| 📎 Document upload + storage     | Multipart upload → MinIO object storage → a FHIR `DocumentReference`      |
| 🔗 Presigned reads, not proxying | Callers fetch document bytes directly from object storage, never through this service |
| 🏥 FHIR-shaped API surface       | `/fhir/DocumentReference` mirrors the real FHIR resource shape and status codes |
| 🔐 Same auth, different protocol | The FHIR endpoints use the identical bearer-JWT/role model as every REST endpoint here |

---

## 🎯 What It Does

```http
POST   /api/v1/encounters                          hasAnyRole('DOCTOR','NURSE','ADMIN')
GET    /api/v1/encounters/{id}                      hasAnyRole('DOCTOR','NURSE','ADMIN','PATIENT')
POST   /api/v1/encounters/{id}/diagnoses            hasRole('DOCTOR')
POST   /api/v1/encounters/{id}/medications          hasRole('DOCTOR')
POST   /api/v1/encounters/{id}/documents            hasAnyRole('DOCTOR','NURSE','ADMIN')   multipart/form-data

GET    /fhir/DocumentReference?patient={id}         hasAnyRole('DOCTOR','NURSE','ADMIN','PATIENT')
GET    /fhir/DocumentReference/{id}                  hasAnyRole('DOCTOR','NURSE','ADMIN','PATIENT')
```

### Document upload

```java
@RequestParam(defaultValue = "http://loinc.org") String typeSystem,
@RequestParam(defaultValue = "18842-5")          String typeCode,
@RequestParam(defaultValue = "Discharge summary") String typeDisplay
```

Defaults to a discharge summary (LOINC `18842-5`) since that's the common case — callers can
override the coding for referral letters, lab reports, or any other document type.

---

# 🏗️ Architecture

Three flows worth separating: the encounter aggregate (diagnoses/medications/labs, all
children of one encounter), the document pipeline (upload → storage → FHIR read), and the two
capabilities that are built but not currently reachable from any endpoint.

```text
══════════════════════════════════════════════════════════════════════════════════════════════
                                     EMR SERVICE
══════════════════════════════════════════════════════════════════════════════════════════════


    ┌────────────────────────────────────────────────────────────────────────────────────┐
    │              1. ENCOUNTER AGGREGATE — DIAGNOSES, MEDICATIONS, LABS                 │
    └────────────────────────────────────────────────────────────────────────────────────┘

        Client
          │
          │ POST /api/v1/encounters
          ▼
    ┌─────────────────────────────────────┐         ┌──────────────────────────────────┐
    │           EncounterController         │────────►         EncounterService          │
    └──────────────────┬──────────────────┘         └──────────────────┬─────────────────┘
                       │                                                │
       POST /{id}/diagnoses ──► addDiagnosis(id, req)                    │  createEncounter(req)
       POST /{id}/medications ──► addMedication(id, req)                 │  ⚠️ publishes NO event
                       │                                                │
                       ▼                                                ▼
    ┌────────────────────────────────────────────────────────────────────────────┐
    │                                Encounter                                    │
    │  (aggregate root — Diagnosis/Medication/LabResult only exist as its         │
    │   children: cascade=ALL, orphanRemoval=true, no repository of their own)   │
    │                                                                              │
    │  addDiagnosis(d)   ⚠️ publishes NO event                                    │
    │  addMedication(m)  ──► publishes PrescriptionCreatedEvent (the ONLY         │
    │                        encounter-side write that reaches Kafka)             │
    └──────────────────────────────────┬───────────────────────────────────────────┘
                                       ▼
                              emr_db.encounters / diagnoses / medications / lab_results
                                       │
                        (only addMedication reaches here)
                                       ▼
                              EmrEventPublisher.publishPrescriptionCreated(...)
                                       │
                                       ▼
                          outbox ──► OutboxRelay ──► Kafka: emr.events


══════════════════════════════════════════════════════════════════════════════════════════════


    ┌────────────────────────────────────────────────────────────────────────────────────┐
    │           2. DOCUMENT PIPELINE — UPLOAD, STORE, PUBLISH, PRESIGN                  │
    └────────────────────────────────────────────────────────────────────────────────────┘


        Client
          │
          │ POST /{id}/documents  (multipart/form-data)
          ▼
    ┌─────────────────────────────────────┐
    │           EncounterController          │
    └──────────────────┬──────────────────┘
                       │
                       ▼
    ┌────────────────────────────────────────────────────────────────────┐
    │              ClinicalDocumentService.uploadDocument(...)             │
    │                                                                      │
    │  1. encounterRepository.findById(encounterId) — must already exist   │
    │                                                                      │
    │  2. DocumentStorageClient.store(encounterId, filename, ...)          │
    │       objectKey = "encounters/{encounterId}/{uuid}-{sanitized-name}" │
    │       ──► MinioClient.putObject(...)                                 │
    │                                                                      │
    │  3. new ClinicalDocument(encounterId, patientId, authorId,           │
    │         typeSystem, typeCode, typeDisplay, title,                    │
    │         contentType, objectKey, sizeBytes)                           │
    │     status = CURRENT   (mirrors FHIR's document-reference-status)    │
    │     save()                                                           │
    │                                                                      │
    │  4. publish DocumentReferenceCreatedEvent  (emr.events)              │
    │  5. publish NotificationRequestedEvent     (notification.requests)   │
    │     — both via EmrEventPublisher, same outbox, same transaction      │
    │       as the ClinicalDocument save                                   │
    │                                                                      │
    │  6. return toFhirDto(document)                                       │
    │       — builds the AttachmentDto's url via                            │
    │         DocumentStorageClient.presignedGetUrl(objectKey)              │
    └──────────────────┬───────────────────────────────────────────────────┘
                       │
          ┌─────────────┼──────────────┬───────────────────────┐
          ▼             ▼              ▼                       ▼
    ┌───────────┐ ┌───────────┐ ┌──────────────┐    ┌────────────────────┐
    │  MinIO     │ │ Clinical  │ │  emr.events  │    │ notification.       │
    │  bucket    │ │ Document  │ │              │    │ requests             │
    │ (bytes)    │ │Repository │ │              │    │                      │
    └───────────┘ └─────┬─────┘ └──────────────┘    └────────────────────┘
                        ▼
                emr_db.clinical_documents
                (metadata only — bytes never touch this table)


    Reading a document back:

    Client ──► GET /fhir/DocumentReference/{id} ──► ClinicalDocumentService.findById(id)
                                                            │
                                                            ▼
                                              toFhirDto(document) — builds a FRESH
                                              15-minute presigned URL on every read,
                                              not a stored/cached one
                                                            │
                                                            ▼
                                              DocumentReferenceDto{ ..., attachment:
                                                { url: <presigned>, contentType, title } }
                                                            │
                                                            ▼
                             Client fetches the actual bytes DIRECTLY from MinIO —
                             emr-service is never in the data path for a download,
                             only for minting the URL that authorizes one


══════════════════════════════════════════════════════════════════════════════════════════════


    ┌────────────────────────────────────────────────────────────────────────────────────┐
    │         3. BUILT, NOT WIRED UP — TWO CAPABILITIES WITH NO CALLER                   │
    └────────────────────────────────────────────────────────────────────────────────────┘


    AllergyRepository.findAllByPatientId(patientId)
          — a real repository, a real domain entity (Allergy: patientId, substance, severity)
          — NO controller endpoint, NO service method anywhere calls it
          — allergies can be persisted only by a direct repository call from code that
            doesn't exist yet (e.g. a test, or a future AllergyController)


    PatientServiceClient.getById(id, authorization)
          — a real RestClient bean (RestClientConfig), backed by DownstreamServicesProperties
            (services.patient.base-url), documented in its own class comment as existing
            "for the referral workflow"
          — NO EncounterService or ClinicalDocumentService method calls it
          — this is the REST-to-patient-service path other platform docs describe as
            emr-service's "referral workflow" — the client is fully built (auth-forwarding,
            404 handling, a BusinessException on downstream failure) but nothing in this
            service currently triggers it
```

---

# 📦 Package Structure

```text
emr-service/
└── src/main/java/.../emr/
    │
    ├── web/
    │   ├── EncounterController
    │   ├── FhirDocumentReferenceController
    │   └── dto/
    │       ├── EncounterRequest / EncounterResponse
    │       ├── DiagnosisRequest / MedicationRequest
    │       └── fhir/
    │           ├── DocumentReferenceDto
    │           ├── BundleDto / BundleEntryDto
    │           ├── AttachmentDto
    │           ├── CodeableConceptDto / CodingDto
    │           ├── ReferenceDto
    │           └── DocumentContentDto / DocumentContextDto
    │
    ├── service/
    │   ├── EncounterService
    │   └── ClinicalDocumentService
    │
    ├── storage/
    │   └── DocumentStorageClient       — wraps the MinIO SDK
    │
    ├── patient/
    │   ├── PatientServiceClient        — built; not currently called (see above)
    │   └── PatientDto
    │
    ├── event/
    │   └── EmrEventPublisher
    │
    ├── config/
    │   ├── MinioConfig / MinioProperties
    │   ├── RestClientConfig
    │   └── DownstreamServicesProperties
    │
    ├── repository/
    │   ├── EncounterRepository
    │   ├── ClinicalDocumentRepository
    │   └── AllergyRepository            — built; not currently called (see above)
    │
    └── domain/
        ├── Encounter    (aggregate root)
        ├── Diagnosis    (child of Encounter)
        ├── Medication   (child of Encounter)
        ├── LabResult    (child of Encounter)
        ├── ClinicalDocument
        ├── DocumentStatus
        └── Allergy      (patient-level, NOT a child of Encounter)
```

---

# 🩺 Encounter as the Aggregate Root

`Diagnosis`, `Medication`, and `LabResult` all carry `@ManyToOne` back to `Encounter` with
`cascade = CascadeType.ALL, orphanRemoval = true` on the parent side, and none of the three has
its own Spring Data repository. That's a deliberate modeling choice, stated directly in
`Encounter`'s own class comment: none of these three things make sense detached from the visit
that produced them, so they're reached only through the `Encounter` they belong to —
`encounter.addDiagnosis(d)`, not `diagnosisRepository.save(d)`.

`Allergy` is the one exception on purpose: it's patient-level, not per-encounter (it needs to
persist across visits and be visible at a glance regardless of which encounter someone is
looking at), which is why it alone has its own top-level repository — even though, per the
diagram above, nothing calls that repository today.

---

# 📎 Why Documents Are Presigned, Not Proxied

`DocumentStorageClient.store(...)` and `presignedGetUrl(...)` are the only two operations this
service performs against MinIO directly. Every actual byte transfer — the upload's `PutObject`
aside — happens client-to-storage, not client-to-service-to-storage:

```text
Upload:  Client ──► emr-service ──► MinIO       (emr-service IS in the write path)
Download: Client ──► MinIO directly              (emr-service is NOT in the read path —
                      (using a presigned URL       it only mints the authorization)
                       emr-service generated)
```

A fresh 15-minute presigned URL is generated on *every* read (`toFhirDto` calls
`presignedGetUrl` each time it's invoked, not once at upload time and cached) — so a
`DocumentReference` fetched today and one fetched tomorrow carry different, independently
time-boxed URLs, and emr-service's own request volume never scales with document *download*
traffic, only with document *metadata* traffic.

---

# 🚀 Where This Is Headed

Object storage moves from MinIO to a managed cloud store as the platform's Azure deployment
matures (see [`../../docs/cloud/azure/data-services.md`](../../docs/cloud/azure/data-services.md)) — the document
client is moving from the MinIO SDK to the native Azure Blob SDK, converging on the same
metadata model either way. The two built-but-unwired capabilities above are the natural next
endpoints to add: an `AllergyController` giving `AllergyRepository` a caller, and a referral
endpoint that finally exercises `PatientServiceClient` — both already have their data-access
layer done, just no HTTP surface calling into them yet. `EncounterService.createEncounter` and
`addDiagnosis` are also candidates to start publishing events, matching `addMedication`'s
existing `PrescriptionCreatedEvent` pattern, so `audit-service`'s trail captures encounter
creation and diagnosis additions the same way it already captures prescriptions.

---

See [`../../docs/architecture/README.md`](../../docs/architecture/README.md) for how this fits into the platform.
