package com.healthplatform.emr.domain;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "diagnoses")
public class Diagnosis {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "encounter_id")
    private Encounter encounter;

    @Column(nullable = false)
    private String icd10Code;

    @Column(nullable = false)
    private String description;

    protected Diagnosis() {}

    public Diagnosis(String icd10Code, String description) {
        this.icd10Code = icd10Code;
        this.description = description;
    }

    void assignTo(Encounter encounter) { this.encounter = encounter; }

    public UUID getId() { return id; }
    public String getIcd10Code() { return icd10Code; }
    public String getDescription() { return description; }
}
