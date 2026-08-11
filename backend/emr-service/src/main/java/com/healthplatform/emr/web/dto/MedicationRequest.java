package com.healthplatform.emr.web.dto;

import jakarta.validation.constraints.NotBlank;

public record MedicationRequest(@NotBlank String name, @NotBlank String dosage) {}
