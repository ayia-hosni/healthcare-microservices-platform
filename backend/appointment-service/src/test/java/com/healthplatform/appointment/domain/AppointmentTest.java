package com.healthplatform.appointment.domain;

import com.healthplatform.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Direct coverage of the Appointment state machine (cancel/confirm/reschedule guards) — no
 * mocks needed since these are plain in-memory transitions on the aggregate itself.
 */
class AppointmentTest {

    private final Instant start = Instant.now().plus(2, ChronoUnit.DAYS);
    private final Instant end = start.plus(30, ChronoUnit.MINUTES);

    private Appointment newAppointment() {
        return new Appointment(UUID.randomUUID(), UUID.randomUUID(), start, end, "key-" + UUID.randomUUID());
    }

    @Test
    void newAppointmentStartsScheduled() {
        Appointment appointment = newAppointment();
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.SCHEDULED);
    }

    @Test
    void cancelSetsStatusAndReason() {
        Appointment appointment = newAppointment();

        appointment.cancel("patient request");

        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(appointment.getCancellationReason()).isEqualTo("patient request");
    }

    @Test
    void confirmMovesScheduledToConfirmed() {
        Appointment appointment = newAppointment();

        appointment.confirm();

        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
    }

    @Test
    void confirmRejectsAlreadyConfirmedAppointment() {
        Appointment appointment = newAppointment();
        appointment.confirm();

        assertThatThrownBy(appointment::confirm)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("scheduled");
    }

    @Test
    void rescheduleUpdatesTimesAndResetsToScheduled() {
        Appointment appointment = newAppointment();
        appointment.confirm();
        Instant newStart = start.plus(1, ChronoUnit.DAYS);
        Instant newEnd = newStart.plus(30, ChronoUnit.MINUTES);

        appointment.reschedule(newStart, newEnd);

        assertThat(appointment.getScheduledStart()).isEqualTo(newStart);
        assertThat(appointment.getScheduledEnd()).isEqualTo(newEnd);
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.SCHEDULED);
    }

    @Test
    void rescheduleRejectsCancelledAppointment() {
        Appointment appointment = newAppointment();
        appointment.cancel("no longer needed");

        assertThatThrownBy(() -> appointment.reschedule(start.plus(1, ChronoUnit.DAYS), end.plus(1, ChronoUnit.DAYS)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("CANCELLED");
    }

    @Test
    void completeMovesScheduledToCompleted() {
        Appointment appointment = newAppointment();

        appointment.complete();

        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.COMPLETED);
    }

    @Test
    void completeAllowsConfirmedAppointmentToo() {
        Appointment appointment = newAppointment();
        appointment.confirm();

        appointment.complete();

        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.COMPLETED);
    }

    @Test
    void completeRejectsCancelledAppointment() {
        Appointment appointment = newAppointment();
        appointment.cancel("no longer needed");

        assertThatThrownBy(appointment::complete)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("CANCELLED");
    }

    @Test
    void completeRejectsAlreadyCompletedAppointment() {
        Appointment appointment = newAppointment();
        appointment.complete();

        assertThatThrownBy(appointment::complete)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("COMPLETED");
    }

    @Test
    void markNoShowMovesScheduledToNoShow() {
        Appointment appointment = newAppointment();

        appointment.markNoShow();

        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.NO_SHOW);
    }

    @Test
    void markNoShowRejectsConfirmedAppointment() {
        Appointment appointment = newAppointment();
        appointment.confirm();

        assertThatThrownBy(appointment::markNoShow)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("CONFIRMED");
    }

    @Test
    void markNoShowRejectsCancelledAppointment() {
        Appointment appointment = newAppointment();
        appointment.cancel("no longer needed");

        assertThatThrownBy(appointment::markNoShow)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("CANCELLED");
    }
}
