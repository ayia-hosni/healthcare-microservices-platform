package com.healthplatform.common.events;

/** Payload for the event published when an appointment visit is finished. */
public record AppointmentCompletedEvent(
        String appointmentId,
        String patientId,
        String doctorId
) {}
