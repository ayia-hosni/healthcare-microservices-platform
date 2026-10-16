package com.healthplatform.appointment.event;

import com.healthplatform.common.events.AppointmentCancelledEvent;
import com.healthplatform.common.events.AppointmentCompletedEvent;
import com.healthplatform.common.events.AppointmentCreatedEvent;
import com.healthplatform.common.events.AppointmentNoShowEvent;
import com.healthplatform.common.events.AppointmentRescheduledEvent;
import com.healthplatform.common.events.DomainEvent;
import com.healthplatform.common.events.Topics;
import com.healthplatform.common.outbox.OutboxWriter;
import org.springframework.stereotype.Component;

/**
 * Writes to the outbox (see ADR-0002) instead of calling KafkaTemplate directly: the write
 * lands in the same transaction as the appointment change, so a crash between "booking
 * committed" and "event shipped" can no longer drop the event — OutboxRelay ships it
 * independently, on its own retry schedule.
 */
@Component
public class AppointmentEventPublisher {

    private final OutboxWriter outboxWriter;

    public AppointmentEventPublisher(OutboxWriter outboxWriter) {
        this.outboxWriter = outboxWriter;
    }

    public void publishCreated(String appointmentId, AppointmentCreatedEvent payload, String correlationId) {
        outboxWriter.enqueue(Topics.APPOINTMENT_EVENTS, appointmentId,
                DomainEvent.of(appointmentId, "AppointmentCreatedEvent", correlationId, payload));
    }

    public void publishCancelled(String appointmentId, AppointmentCancelledEvent payload, String correlationId) {
        outboxWriter.enqueue(Topics.APPOINTMENT_EVENTS, appointmentId,
                DomainEvent.of(appointmentId, "AppointmentCancelledEvent", correlationId, payload));
    }

    public void publishRescheduled(String appointmentId, AppointmentRescheduledEvent payload, String correlationId) {
        outboxWriter.enqueue(Topics.APPOINTMENT_EVENTS, appointmentId,
                DomainEvent.of(appointmentId, "AppointmentRescheduledEvent", correlationId, payload));
    }

    public void publishCompleted(String appointmentId, AppointmentCompletedEvent payload, String correlationId) {
        outboxWriter.enqueue(Topics.APPOINTMENT_EVENTS, appointmentId,
                DomainEvent.of(appointmentId, "AppointmentCompletedEvent", correlationId, payload));
    }

    public void publishNoShow(String appointmentId, AppointmentNoShowEvent payload, String correlationId) {
        outboxWriter.enqueue(Topics.APPOINTMENT_EVENTS, appointmentId,
                DomainEvent.of(appointmentId, "AppointmentNoShowEvent", correlationId, payload));
    }
}
