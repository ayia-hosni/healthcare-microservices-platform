package com.healthplatform.appointment.service;

import com.healthplatform.appointment.domain.Appointment;
import com.healthplatform.appointment.domain.AppointmentStatus;
import com.healthplatform.appointment.event.AppointmentEventPublisher;
import com.healthplatform.appointment.repository.AppointmentRepository;
import com.healthplatform.appointment.web.dto.AppointmentResponse;
import com.healthplatform.appointment.web.dto.BookAppointmentRequest;
import com.healthplatform.common.exception.BusinessException;
import com.healthplatform.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the booking/cancel/reschedule orchestration in AppointmentService.
 * AppointmentRepository and AppointmentEventPublisher (which itself wraps the outbox — see
 * AppointmentEventPublisher) are mocked; the DB-level unique constraint and the pessimistic
 * lock taken by findConflicting() are covered separately by the Testcontainers integration test,
 * since neither can be meaningfully exercised against a mock.
 */
class AppointmentServiceTest {

    private AppointmentRepository appointmentRepository;
    private AppointmentEventPublisher eventPublisher;
    private AppointmentService appointmentService;

    private final UUID patientId = UUID.randomUUID();
    private final UUID doctorId = UUID.randomUUID();
    private final Instant start = Instant.now().plus(2, ChronoUnit.DAYS);
    private final Instant end = start.plus(30, ChronoUnit.MINUTES);

    @BeforeEach
    void setUp() {
        appointmentRepository = mock(AppointmentRepository.class);
        eventPublisher = mock(AppointmentEventPublisher.class);
        appointmentService = new AppointmentService(appointmentRepository, eventPublisher);
    }

    @Test
    void booksAppointmentWhenSlotIsFree() {
        when(appointmentRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());
        when(appointmentRepository.findConflicting(doctorId, start)).thenReturn(Optional.empty());
        // Simulate JPA assigning the @GeneratedValue id on save(), same as a real persist would.
        when(appointmentRepository.save(any())).thenAnswer(inv -> {
            Appointment saved = inv.getArgument(0);
            setId(saved, UUID.randomUUID());
            return saved;
        });

        AppointmentResponse response = appointmentService.book(
                new BookAppointmentRequest(patientId, doctorId, start, end, "key-1"));

        assertThat(response.status()).isEqualTo(AppointmentStatus.SCHEDULED);
        assertThat(response.doctorId()).isEqualTo(doctorId);
        assertThat(response.patientId()).isEqualTo(patientId);
        verify(appointmentRepository).save(any(Appointment.class));
        verify(eventPublisher).publishCreated(any(), any(), any());
    }

    @Test
    void rejectsDoubleBookingForSameDoctorAndSlot() {
        when(appointmentRepository.findByIdempotencyKey("key-2")).thenReturn(Optional.empty());
        Appointment existingBooking = new Appointment(UUID.randomUUID(), doctorId, start, end, "other-key");
        when(appointmentRepository.findConflicting(doctorId, start)).thenReturn(Optional.of(existingBooking));

        assertThatThrownBy(() -> appointmentService.book(
                new BookAppointmentRequest(patientId, doctorId, start, end, "key-2")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already booked");

        verify(appointmentRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void replayingSameIdempotencyKeyReturnsOriginalInsteadOfCreatingADuplicate() {
        // The guard is the findByIdempotencyKey() short-circuit at the top of book(): a second
        // request with the same key must return the existing appointment without ever reaching
        // the conflict check or save().
        Appointment original = new Appointment(patientId, doctorId, start, end, "key-3");
        when(appointmentRepository.findByIdempotencyKey("key-3")).thenReturn(Optional.of(original));

        AppointmentResponse first = appointmentService.book(
                new BookAppointmentRequest(patientId, doctorId, start, end, "key-3"));
        AppointmentResponse second = appointmentService.book(
                new BookAppointmentRequest(patientId, doctorId, start, end, "key-3"));

        assertThat(first.patientId()).isEqualTo(second.patientId());
        assertThat(first.status()).isEqualTo(second.status());
        verify(appointmentRepository, never()).findConflicting(any(), any());
        verify(appointmentRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void cancelsScheduledAppointmentAndPublishesEvent() {
        UUID id = UUID.randomUUID();
        Appointment appointment = new Appointment(patientId, doctorId, start, end, "key-4");
        setId(appointment, id);
        when(appointmentRepository.findById(id)).thenReturn(Optional.of(appointment));

        AppointmentResponse response = appointmentService.cancel(id, "patient requested", "aya@example.com");

        assertThat(response.status()).isEqualTo(AppointmentStatus.CANCELLED);
        verify(eventPublisher).publishCancelled(any(), any(), any());
    }

    @Test
    void cancelThrowsWhenAppointmentNotFound() {
        UUID id = UUID.randomUUID();
        when(appointmentRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> appointmentService.cancel(id, "reason", "aya@example.com"))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void reschedulesToNewSlotWhenFree() {
        Appointment appointment = new Appointment(patientId, doctorId, start, end, "key-5");
        UUID id = UUID.randomUUID();
        Instant newStart = start.plus(1, ChronoUnit.DAYS);
        Instant newEnd = newStart.plus(30, ChronoUnit.MINUTES);
        when(appointmentRepository.findById(id)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.findConflicting(doctorId, newStart)).thenReturn(Optional.empty());

        AppointmentResponse response = appointmentService.reschedule(id, newStart, newEnd);

        assertThat(response.scheduledStart()).isEqualTo(newStart);
        assertThat(response.scheduledEnd()).isEqualTo(newEnd);
        assertThat(response.status()).isEqualTo(AppointmentStatus.SCHEDULED);
    }

    @Test
    void rescheduleRejectsWhenTargetSlotTakenByAnotherAppointment() {
        Appointment appointment = new Appointment(patientId, doctorId, start, end, "key-6");
        UUID id = UUID.randomUUID();
        Instant newStart = start.plus(1, ChronoUnit.DAYS);
        Instant newEnd = newStart.plus(30, ChronoUnit.MINUTES);
        Appointment conflicting = new Appointment(UUID.randomUUID(), doctorId, newStart, newEnd, "other-key");
        setId(conflicting, UUID.randomUUID()); // a different appointment's real id, distinct from `id`
        when(appointmentRepository.findById(id)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.findConflicting(doctorId, newStart)).thenReturn(Optional.of(conflicting));

        assertThatThrownBy(() -> appointmentService.reschedule(id, newStart, newEnd))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already booked");
    }

    @Test
    void rescheduleIgnoresConflictWithTheSameAppointmentBeingRescheduled() {
        // findConflicting() has no way to exclude the appointment's own current row at the query
        // level, so reschedule() filters out a "conflict" that is actually itself
        // (.filter(other -> !other.getId().equals(id))). Verify that filter actually works.
        UUID id = UUID.randomUUID();
        Appointment appointment = new Appointment(patientId, doctorId, start, end, "key-7");
        setId(appointment, id);
        Instant newStart = start.plus(1, ChronoUnit.DAYS);
        Instant newEnd = newStart.plus(30, ChronoUnit.MINUTES);
        when(appointmentRepository.findById(id)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.findConflicting(doctorId, newStart)).thenReturn(Optional.of(appointment));

        AppointmentResponse response = appointmentService.reschedule(id, newStart, newEnd);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.scheduledStart()).isEqualTo(newStart);
    }

    @Test
    void getByIdReturnsAppointment() {
        UUID id = UUID.randomUUID();
        Appointment appointment = new Appointment(patientId, doctorId, start, end, "key-8");
        when(appointmentRepository.findById(id)).thenReturn(Optional.of(appointment));

        AppointmentResponse response = appointmentService.getById(id);

        assertThat(response.patientId()).isEqualTo(patientId);
    }

    @Test
    void getByIdThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(appointmentRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> appointmentService.getById(id)).isInstanceOf(ResourceNotFoundException.class);
    }

    /** JPA-generated id is null until persisted; set it via reflection to simulate a fetched/saved row. */
    private static void setId(Appointment appointment, UUID id) {
        try {
            var field = Appointment.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(appointment, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
