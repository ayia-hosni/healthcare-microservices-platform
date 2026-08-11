package com.healthplatform.emr.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * An Encounter is the aggregate root for a single clinical visit. Diagnoses, medications,
 * lab results, and notes are all children of an encounter — they never make sense detached
 * from the visit that produced them, which is why they're modeled as part of this aggregate
 * rather than as top-level entities with their own repositories.
 */
@Entity
@Table(name = "encounters")
public class Encounter {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private UUID patientId;

    @Column(nullable = false)
    private UUID doctorId;

    private UUID appointmentId;

    @Column(nullable = false)
    private Instant encounterDate = Instant.now();

    @Column(columnDefinition = "TEXT")
    private String notes;

    @OneToMany(mappedBy = "encounter", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Diagnosis> diagnoses = new ArrayList<>();

    @OneToMany(mappedBy = "encounter", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Medication> medications = new ArrayList<>();

    @OneToMany(mappedBy = "encounter", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<LabResult> labResults = new ArrayList<>();

    protected Encounter() {}

    public Encounter(UUID patientId, UUID doctorId, UUID appointmentId, String notes) {
        this.patientId = patientId;
        this.doctorId = doctorId;
        this.appointmentId = appointmentId;
        this.notes = notes;
    }

    public void addDiagnosis(Diagnosis d) { d.assignTo(this); diagnoses.add(d); }
    public void addMedication(Medication m) { m.assignTo(this); medications.add(m); }
    public void addLabResult(LabResult l) { l.assignTo(this); labResults.add(l); }

    public UUID getId() { return id; }
    public UUID getPatientId() { return patientId; }
    public UUID getDoctorId() { return doctorId; }
    public UUID getAppointmentId() { return appointmentId; }
    public Instant getEncounterDate() { return encounterDate; }
    public String getNotes() { return notes; }
    public List<Diagnosis> getDiagnoses() { return diagnoses; }
    public List<Medication> getMedications() { return medications; }
    public List<LabResult> getLabResults() { return labResults; }
}
