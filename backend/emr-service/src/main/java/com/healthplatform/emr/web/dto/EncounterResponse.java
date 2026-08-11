package com.healthplatform.emr.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EncounterResponse(
        UUID id,
        UUID patientId,
        UUID doctorId,
        Instant encounterDate,
        String notes,
        List<String> diagnoses,
        List<String> medications
) {}
