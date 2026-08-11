package com.healthplatform.common.events;

import java.time.LocalDate;

/** Payload for the event published when a new patient completes registration. */
public record PatientRegisteredEvent(
        String patientId,
        String firstName,
        String lastName,
        LocalDate dateOfBirth,
        String email
) {}
