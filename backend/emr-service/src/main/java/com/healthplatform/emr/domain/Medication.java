package com.healthplatform.emr.domain;

import jakarta.persistence.*;
import java.util.UUID;

/** Also referred to as a "prescription" in domain language — publishes PrescriptionCreatedEvent when added. */
@Entity
@Table(name = "medications")
public class Medication {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "encounter_id")
    private Encounter encounter;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String dosage;

    protected Medication() {}

    public Medication(String name, String dosage) {
        this.name = name;
        this.dosage = dosage;
    }

    void assignTo(Encounter encounter) { this.encounter = encounter; }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getDosage() { return dosage; }
}
