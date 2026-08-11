package com.healthplatform.common.events;

/** Payload for the event published when emr-service creates a FHIR DocumentReference for a clinical document. */
public record DocumentReferenceCreatedEvent(
        String documentId,
        String patientId,
        String encounterId,
        String authorId,
        String typeDisplay,
        String contentType,
        String title
) {}
