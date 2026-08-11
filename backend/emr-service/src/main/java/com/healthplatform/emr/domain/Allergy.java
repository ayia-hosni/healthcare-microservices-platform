package com.healthplatform.emr.domain;

import jakarta.persistence.*;
import java.util.UUID;

/** Allergies are patient-level (not per-encounter) — they persist across visits and must be visible at a glance. */
@Entity
@Table(name = "allergies")
public class Allergy {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private UUID patientId;

    @Column(nullable = false)
    private String substance;

    @Column(nullable = false)
    private String severity; // MILD | MODERATE | SEVERE

    protected Allergy() {}

    public Allergy(UUID patientId, String substance, String severity) {
        this.patientId = patientId;
        this.substance = substance;
        this.severity = severity;
    }

    public UUID getId() { return id; }
    public UUID getPatientId() { return patientId; }
    public String getSubstance() { return substance; }
    public String getSeverity() { return severity; }
}
