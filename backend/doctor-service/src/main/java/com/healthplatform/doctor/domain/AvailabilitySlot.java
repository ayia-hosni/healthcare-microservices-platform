package com.healthplatform.doctor.domain;

import jakarta.persistence.*;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.UUID;

/** A recurring weekly availability window (e.g. Monday 09:00-12:00). Appointment-service checks against these. */
@Entity
@Table(name = "availability_slots")
public class AvailabilitySlot {

    // Assigned in the constructor, not @GeneratedValue: addAvailabilitySlot and
    // removeAvailabilitySlot match on getId() before the slot is persisted.
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id")
    private Doctor doctor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DayOfWeek dayOfWeek;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    protected AvailabilitySlot() {}

    public AvailabilitySlot(DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime) {
        this.id = UUID.randomUUID();
        this.dayOfWeek = dayOfWeek;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    void assignTo(Doctor doctor) { this.doctor = doctor; }

    public UUID getId() { return id; }
    public DayOfWeek getDayOfWeek() { return dayOfWeek; }
    public LocalTime getStartTime() { return startTime; }
    public LocalTime getEndTime() { return endTime; }
}
