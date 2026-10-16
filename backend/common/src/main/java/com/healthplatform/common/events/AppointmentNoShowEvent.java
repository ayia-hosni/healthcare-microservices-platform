package com.healthplatform.common.events;

/** Payload for the event published when a patient never showed up for a scheduled appointment. */
public record AppointmentNoShowEvent(
        String appointmentId,
        String patientId,
        String doctorId
) {}
