package com.healthplatform.appointment.repository;

import com.healthplatform.appointment.domain.Appointment;
import com.healthplatform.appointment.domain.AppointmentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

    Optional<Appointment> findByIdempotencyKey(String idempotencyKey);

    /** Pessimistic lock: serializes concurrent booking attempts for the same doctor/slot pair
     *  ahead of hitting the DB unique constraint, so we can fail fast with a clean 409 instead
     *  of surfacing a raw constraint-violation exception to the caller. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Appointment a where a.doctorId = :doctorId and a.scheduledStart = :start and a.status <> 'CANCELLED'")
    Optional<Appointment> findConflicting(UUID doctorId, Instant start);

    List<Appointment> findAllByStatusAndScheduledStartBefore(AppointmentStatus status, Instant cutoff);

    List<Appointment> findAllByStatusAndScheduledStartBetween(AppointmentStatus status, Instant from, Instant to);
}
