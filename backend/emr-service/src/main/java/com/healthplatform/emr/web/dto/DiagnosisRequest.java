package com.healthplatform.emr.web.dto;

import jakarta.validation.constraints.NotBlank;

public record DiagnosisRequest(@NotBlank String icd10Code, @NotBlank String description) {}
