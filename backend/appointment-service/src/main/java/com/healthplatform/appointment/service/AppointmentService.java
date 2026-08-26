package com.healthplatform.appointment.service;

import com.healthplatform.appointment.domain.Appointment;
import com.healthplatform.appointment.event.AppointmentEventPublisher;
import com.healthplatform.appointment.grpc.BookingValidationClient;
import com.healthplatform.appointment.repository.AppointmentRepository;
import com.healthplatform.appointment.web.dto.AppointmentResponse;
import com.healthplatform.appointment.web.dto.BookAppointmentRequest;
import com.healthplatform.common.events.AppointmentCancelledEvent;
import com.healthplatform.common.events.AppointmentCreatedEvent;
import com.healthplatform.common.exception.BusinessException;
import com.healthplatform.common.exception.ResourceNotFoundException;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Booking is the entry point to a SAGA that spans appointment -> notification -> billing:
 *
 *   1. appointment-service creates the Appointment (SCHEDULED) and publishes AppointmentCreatedEvent.
 *   2. notification-service consumes it and sends a confirmation email/SMS (best-effort, not compensated).
 *   3. billing-service consumes it and generates a pending invoice for the visit.
 *   4. If billing fails irrecoverably (e.g. invalid insurance), it publishes a compensating
 *      event (BillingFailedEvent — an extension point not wired up in this learning build)
 *      which this service would consume to auto-cancel the appointment and free the slot.
 *
 * This is a CHOREOGRAPHED saga (services react to each other's events) rather than an
 * ORCHESTRATED one (a central saga coordinator) — appropriate here because the compensation
 * logic is simple and linear. A dedicated orchestrator would earn its complexity once a third
 * or fourth compensating branch shows up (e.g. EMR pre-authorization checks).
 *
 * Reliability: publishing directly to Kafka after the DB commit (as done here) has a small
 * window where the DB write succeeds but the process crashes before the Kafka send — the
 * OUTBOX PATTERN (writing the event to an `outbox` table in the same transaction, then a
 * separate poller relaying it to Kafka) closes that gap and is the production-grade version
 * of this same flow.
 */
@Service
public class AppointmentService {

    private final AppointmentRepository appointmentRepository;
    private final AppointmentEventPublisher eventPublisher;
    private final BookingValidationClient bookingValidationClient;

    public AppointmentService(AppointmentRepository appointmentRepository, AppointmentEventPublisher eventPublisher,
                               BookingValidationClient bookingValidationClient) {
        this.appointmentRepository = appointmentRepository;
        this.eventPublisher = eventPublisher;
        this.bookingValidationClient = bookingValidationClient;
    }

    @Transactional
    public AppointmentResponse book(BookAppointmentRequest request) {
        // Idempotency: a retried request with the same key returns the original result
        // instead of creating a duplicate appointment.
        var existing = appointmentRepository.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            return toResponse(existing.get());
        }

        // Synchronous existence checks via gRPC (PatientLookup/DoctorLookup) — previously
        // this trusted a client-supplied patientId/doctorId outright.
        bookingValidationClient.requirePatientExists(request.patientId());
        bookingValidationClient.requireDoctorExists(request.doctorId());

        appointmentRepository.findConflicting(request.doctorId(), request.scheduledStart())
                .ifPresent(a -> {
                    throw new BusinessException("SLOT_TAKEN", "This doctor is already booked for that time");
                });

        Appointment appointment = new Appointment(request.patientId(), request.doctorId(),
                request.scheduledStart(), request.scheduledEnd(), request.idempotencyKey());
        appointmentRepository.save(appointment);

        eventPublisher.publishCreated(
                appointment.getId().toString(),
                new AppointmentCreatedEvent(appointment.getId().toString(), appointment.getPatientId().toString(),
                        appointment.getDoctorId().toString(), appointment.getScheduledStart(), appointment.getScheduledEnd()),
                MDC.get("correlationId")
        );

        return toResponse(appointment);
    }

    @Transactional
    public AppointmentResponse cancel(UUID id, String reason, String cancelledBy) {
        Appointment appointment = getOrThrow(id);
        appointment.cancel(reason);

        eventPublisher.publishCancelled(
                appointment.getId().toString(),
                new AppointmentCancelledEvent(appointment.getId().toString(), cancelledBy, reason),
                MDC.get("correlationId")
        );

        return toResponse(appointment);
    }

    @Transactional
    public AppointmentResponse reschedule(UUID id, java.time.Instant newStart, java.time.Instant newEnd) {
        Appointment appointment = getOrThrow(id);

        appointmentRepository.findConflicting(appointment.getDoctorId(), newStart)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(a -> {
                    throw new BusinessException("SLOT_TAKEN", "This doctor is already booked for that time");
                });

        appointment.reschedule(newStart, newEnd);
        return toResponse(appointment);
    }

    @Transactional(readOnly = true)
    public AppointmentResponse getById(UUID id) {
        return toResponse(getOrThrow(id));
    }

    @Transactional(readOnly = true)
    public java.util.List<AppointmentResponse> getByPatientId(UUID patientId) {
        return appointmentRepository.findAllByPatientIdOrderByScheduledStartDesc(patientId).stream()
                .map(this::toResponse).toList();
    }

    private Appointment getOrThrow(UUID id) {
        return appointmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found: " + id));
    }

    private AppointmentResponse toResponse(Appointment a) {
        return new AppointmentResponse(a.getId(), a.getPatientId(), a.getDoctorId(),
                a.getScheduledStart(), a.getScheduledEnd(), a.getStatus());
    }
}
