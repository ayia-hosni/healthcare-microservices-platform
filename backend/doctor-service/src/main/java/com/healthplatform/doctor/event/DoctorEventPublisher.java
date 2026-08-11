package com.healthplatform.doctor.event;

import com.healthplatform.common.events.DoctorCreatedEvent;
import com.healthplatform.common.events.DomainEvent;
import com.healthplatform.common.events.Topics;
import com.healthplatform.common.outbox.OutboxWriter;
import org.springframework.stereotype.Component;

@Component
public class DoctorEventPublisher {

    private final OutboxWriter outboxWriter;

    public DoctorEventPublisher(OutboxWriter outboxWriter) {
        this.outboxWriter = outboxWriter;
    }

    public void publishDoctorCreated(String doctorId, DoctorCreatedEvent payload, String correlationId) {
        var event = DomainEvent.of(doctorId, "DoctorCreatedEvent", correlationId, payload);
        outboxWriter.enqueue(Topics.DOCTOR_EVENTS, doctorId, event);
    }
}
