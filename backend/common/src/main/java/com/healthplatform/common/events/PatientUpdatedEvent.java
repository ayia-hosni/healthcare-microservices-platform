package com.healthplatform.common.events;

/** Payload for the event published when a patient's demographic/contact profile changes. */
public record PatientUpdatedEvent(
        String patientId,
        String phoneNumber
) {}
