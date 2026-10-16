package com.healthplatform.appointment.domain;

import com.healthplatform.common.exception.BusinessException;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * A patient waiting for a slot to open up with a specific doctor. When a slot frees,
 * WaitingListService.notifyNextForDoctor picks the oldest WAITING entry (FIFO by createdAt).
 *
 * At most one active (WAITING) entry per patient/doctor pair: partial unique index in the
 * V3 migration, plus a service-level check.
 */
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WaitingListStatus status;

    /** Set on the move to NOTIFIED; the expiry job's grace period counts from here. */
    private Instant notifiedAt;

    @Version
    private Long version;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected WaitingListEntry() {}

    public WaitingListEntry(UUID patientId, UUID doctorId) {
        this.patientId = patientId;
        this.doctorId = doctorId;
        this.status = WaitingListStatus.WAITING;
    }

    /** Front of the queue has been told a slot opened up. */
    public void markNotified() {
        requireStatus(WaitingListStatus.WAITING, "notify");
        this.status = WaitingListStatus.NOTIFIED;
        this.notifiedAt = Instant.now();
    }

    /** The patient booked after being notified. */
    public void fulfill() {
        requireStatus(WaitingListStatus.NOTIFIED, "fulfill");
        this.status = WaitingListStatus.FULFILLED;
    }

    /** The patient (or staff) removed this entry from the queue. Allowed while still active. */
    public void cancel() {
        if (status != WaitingListStatus.WAITING && status != WaitingListStatus.NOTIFIED) {
            throw new BusinessException("INVALID_STATE_TRANSITION",
                    "Cannot leave the waiting list from state " + status);
        }
        this.status = WaitingListStatus.CANCELLED;
    }

    /** Notified but the grace period elapsed without a booking. */
    public void expire() {
        requireStatus(WaitingListStatus.NOTIFIED, "expire");
        this.status = WaitingListStatus.EXPIRED;
    }

    private void requireStatus(WaitingListStatus expected, String action) {
        if (status != expected) {
            throw new BusinessException("INVALID_STATE_TRANSITION",
                    "Cannot " + action + " a waiting-list entry in state " + status + " (expected " + expected + ")");
        }
    }

    public UUID getId() { return id; }
    public UUID getPatientId() { return patientId; }
    public UUID getDoctorId() { return doctorId; }
    public WaitingListStatus getStatus() { return status; }
    public Instant getNotifiedAt() { return notifiedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
