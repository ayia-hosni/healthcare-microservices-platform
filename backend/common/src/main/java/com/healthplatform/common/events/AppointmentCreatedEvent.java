package com.healthplatform.common.events;

import java.time.Instant;

/** Payload for the event published when an appointment is booked. */
public record AppointmentCreatedEvent(
        String appointmentId,
        String patientId,
        String doctorId,
        Instant scheduledStart,
        Instant scheduledEnd
) {}
