package com.healthplatform.doctor.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record DoctorRequest(
        @NotNull UUID id,
        @NotBlank String firstName,
        @NotBlank String lastName,
        @NotBlank String specialty,
        @NotBlank String departmentName
) {}
