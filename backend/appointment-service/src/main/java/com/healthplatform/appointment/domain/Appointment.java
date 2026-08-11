package com.healthplatform.appointment.domain;

import com.healthplatform.common.exception.BusinessException;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Appointment aggregate root.
 *
 * Concurrency strategy: double-booking is prevented by a DB-level unique constraint on
 * (doctor_id, scheduled_start) rather than only application-level checks — see V1 migration.
 * That constraint is the actual source of truth; the pessimistic lock taken in
 * AppointmentBookingService is a best-effort fast-fail so the caller gets a clean
 * BusinessException instead of a raw constraint-violation exception in the common case.
 */
@Entity
@Table(name = "appointments",
       uniqueConstraints = @UniqueConstraint(name = "uk_doctor_slot", columnNames = {"doctor_id", "scheduled_start"}))
public class Appointment {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private UUID patientId;

    @Column(nullable = false)
    private UUID doctorId;

    @Column(nullable = false)
    private Instant scheduledStart;

    @Column(nullable = false)
    private Instant scheduledEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AppointmentStatus status;

    /** Client-supplied idempotency key so retried "book appointment" requests don't create duplicates. */
    @Column(nullable = false, unique = true)
    private String idempotencyKey;

    private String cancellationReason;

    @Version
    private Long version;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected Appointment() {}

    public Appointment(UUID patientId, UUID doctorId, Instant scheduledStart, Instant scheduledEnd, String idempotencyKey) {
        this.patientId = patientId;
        this.doctorId = doctorId;
        this.scheduledStart = scheduledStart;
        this.scheduledEnd = scheduledEnd;
        this.status = AppointmentStatus.SCHEDULED;
        this.idempotencyKey = idempotencyKey;
    }

    public void cancel(String reason) {
        if (status == AppointmentStatus.COMPLETED) {
            throw new BusinessException("INVALID_STATE_TRANSITION", "Cannot cancel a completed appointment");
        }
        this.status = AppointmentStatus.CANCELLED;
        this.cancellationReason = reason;
    }

    public void confirm() {
        if (status != AppointmentStatus.SCHEDULED) {
            throw new BusinessException("INVALID_STATE_TRANSITION", "Only a scheduled appointment can be confirmed");
        }
        this.status = AppointmentStatus.CONFIRMED;
    }

    public void reschedule(Instant newStart, Instant newEnd) {
        if (status == AppointmentStatus.CANCELLED || status == AppointmentStatus.COMPLETED) {
            throw new BusinessException("INVALID_STATE_TRANSITION", "Cannot reschedule a " + status + " appointment");
        }
        this.scheduledStart = newStart;
        this.scheduledEnd = newEnd;
        this.status = AppointmentStatus.SCHEDULED;
    }

    public UUID getId() { return id; }
    public UUID getPatientId() { return patientId; }
    public UUID getDoctorId() { return doctorId; }
    public Instant getScheduledStart() { return scheduledStart; }
    public Instant getScheduledEnd() { return scheduledEnd; }
    public AppointmentStatus getStatus() { return status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getCancellationReason() { return cancellationReason; }
}
