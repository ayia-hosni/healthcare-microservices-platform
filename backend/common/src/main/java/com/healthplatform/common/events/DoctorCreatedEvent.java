package com.healthplatform.common.events;

/** Payload for the event published when a doctor profile is created. */
public record DoctorCreatedEvent(
        String doctorId,
        String firstName,
        String lastName,
        String specialty,
        String departmentId
) {}
