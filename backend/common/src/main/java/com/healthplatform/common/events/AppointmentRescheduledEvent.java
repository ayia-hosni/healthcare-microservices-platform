package com.healthplatform.common.events;

import java.time.Instant;

/** Payload for the event published when an appointment is moved to a new time. */
public record AppointmentRescheduledEvent(
        String appointmentId,
        Instant previousStart,
        Instant newStart,
        Instant newEnd
) {}
