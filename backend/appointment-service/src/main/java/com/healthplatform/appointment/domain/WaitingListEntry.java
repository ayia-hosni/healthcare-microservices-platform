package com.healthplatform.appointment.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** A patient waiting for a slot to open up with a specific doctor. FIFO by createdAt when notifying. */
@Entity
@Table(name = "waiting_list_entries")
public class WaitingListEntry {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private UUID patientId;

    @Column(nullable = false)
    private UUID doctorId;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected WaitingListEntry() {}

    public WaitingListEntry(UUID patientId, UUID doctorId) {
        this.patientId = patientId;
        this.doctorId = doctorId;
    }

    public UUID getId() { return id; }
    public UUID getPatientId() { return patientId; }
    public UUID getDoctorId() { return doctorId; }
    public Instant getCreatedAt() { return createdAt; }
}
