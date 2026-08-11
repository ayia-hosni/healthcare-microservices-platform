package com.healthplatform.emr.event;

import com.healthplatform.common.events.DocumentReferenceCreatedEvent;
import com.healthplatform.common.events.DomainEvent;
import com.healthplatform.common.events.NotificationRequestedEvent;
import com.healthplatform.common.events.PrescriptionCreatedEvent;
import com.healthplatform.common.events.Topics;
import com.healthplatform.common.outbox.OutboxWriter;
import org.springframework.stereotype.Component;

/** Writes to the outbox (ADR-0002) so each publish commits atomically with its business write. */
@Component
public class EmrEventPublisher {

    private final OutboxWriter outboxWriter;

    public EmrEventPublisher(OutboxWriter outboxWriter) {
        this.outboxWriter = outboxWriter;
    }

    public void publishPrescriptionCreated(String prescriptionId, PrescriptionCreatedEvent payload, String correlationId) {
        outboxWriter.enqueue(Topics.EMR_EVENTS, prescriptionId,
                DomainEvent.of(prescriptionId, "PrescriptionCreatedEvent", correlationId, payload));
    }

    public void publishDocumentReferenceCreated(String documentId, DocumentReferenceCreatedEvent payload, String correlationId) {
        outboxWriter.enqueue(Topics.EMR_EVENTS, documentId,
                DomainEvent.of(documentId, "DocumentReferenceCreatedEvent", correlationId, payload));
    }

    public void publishNotificationRequested(String aggregateId, NotificationRequestedEvent payload, String correlationId) {
        outboxWriter.enqueue(Topics.NOTIFICATION_REQUESTS, aggregateId,
                DomainEvent.of(aggregateId, "NotificationRequestedEvent", correlationId, payload));
    }
}
