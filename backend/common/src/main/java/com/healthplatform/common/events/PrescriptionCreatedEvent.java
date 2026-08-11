package com.healthplatform.common.events;

/** Payload for the event published when a prescription/medication order is created in the EMR. */
public record PrescriptionCreatedEvent(
        String prescriptionId,
        String patientId,
        String doctorId,
        String medication,
        String dosage
) {}
