package com.healthplatform.patient.web.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PatientResponse(
        UUID id,
        String firstName,
        String lastName,
        LocalDate dateOfBirth,
        String email,
        String phoneNumber,
        boolean consentGranted,
        Instant consentUpdatedAt
) {}
