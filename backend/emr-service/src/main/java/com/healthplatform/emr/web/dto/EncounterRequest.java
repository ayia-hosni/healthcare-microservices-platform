package com.healthplatform.emr.web.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record EncounterRequest(
        @NotNull UUID patientId,
        @NotNull UUID doctorId,
        UUID appointmentId,
        String notes
) {}
