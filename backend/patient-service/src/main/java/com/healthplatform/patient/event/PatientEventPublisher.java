package com.healthplatform.patient.event;

import com.healthplatform.common.events.DomainEvent;
import com.healthplatform.common.events.PatientRegisteredEvent;
import com.healthplatform.common.events.Topics;
import com.healthplatform.common.outbox.OutboxWriter;
import org.springframework.stereotype.Component;

/** Writes to the outbox (ADR-0002) so the publish commits atomically with the patient write. */
@Component
public class PatientEventPublisher {

    private final OutboxWriter outboxWriter;

    public PatientEventPublisher(OutboxWriter outboxWriter) {
        this.outboxWriter = outboxWriter;
    }

    public void publishPatientRegistered(String patientId, PatientRegisteredEvent payload, String correlationId) {
        var event = DomainEvent.of(patientId, "PatientRegisteredEvent", correlationId, payload);
        outboxWriter.enqueue(Topics.PATIENT_EVENTS, patientId, event);
    }
}
