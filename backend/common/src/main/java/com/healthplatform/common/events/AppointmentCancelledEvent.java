package com.healthplatform.common.events;

/** Payload for the event published when an appointment is cancelled. */
public record AppointmentCancelledEvent(
        String appointmentId,
        String cancelledBy,
        String reason
) {}
