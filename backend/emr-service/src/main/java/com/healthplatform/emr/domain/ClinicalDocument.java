package com.healthplatform.emr.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Metadata for a clinical document (discharge summary, referral letter, lab report, ...)
 * produced during an encounter. The bytes live in object storage; this row is what gets
 * translated into a FHIR DocumentReference at the web layer — the entity itself stays a
 * plain internal record, same as Allergy or Diagnosis, so the FHIR wire shape never leaks
 * into the domain model.
 */
@Entity
@Table(name = "clinical_documents")
public class ClinicalDocument {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private UUID encounterId;

    @Column(nullable = false)
    private UUID patientId;

    @Column(nullable = false)
    private UUID authorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentStatus status = DocumentStatus.CURRENT;

    @Column(nullable = false)
    private String typeSystem;

    @Column(nullable = false)
    private String typeCode;

    @Column(nullable = false)
    private String typeDisplay;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String contentType;

    @Column(nullable = false)
    private String objectKey;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected ClinicalDocument() {}

    public ClinicalDocument(UUID encounterId, UUID patientId, UUID authorId, String typeSystem, String typeCode,
                             String typeDisplay, String title, String contentType, String objectKey, long sizeBytes) {
        this.encounterId = encounterId;
        this.patientId = patientId;
        this.authorId = authorId;
        this.typeSystem = typeSystem;
        this.typeCode = typeCode;
        this.typeDisplay = typeDisplay;
        this.title = title;
        this.contentType = contentType;
        this.objectKey = objectKey;
        this.sizeBytes = sizeBytes;
    }

    public UUID getId() { return id; }
    public UUID getEncounterId() { return encounterId; }
    public UUID getPatientId() { return patientId; }
    public UUID getAuthorId() { return authorId; }
    public DocumentStatus getStatus() { return status; }
    public String getTypeSystem() { return typeSystem; }
    public String getTypeCode() { return typeCode; }
    public String getTypeDisplay() { return typeDisplay; }
    public String getTitle() { return title; }
    public String getContentType() { return contentType; }
    public String getObjectKey() { return objectKey; }
    public long getSizeBytes() { return sizeBytes; }
    public Instant getCreatedAt() { return createdAt; }
}
