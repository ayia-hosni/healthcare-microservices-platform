package com.healthplatform.billing.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;

import java.time.LocalDate;

public record EligibilityRequest(
        @NotBlank String memberId,
        @NotBlank String payerId,
        @NotNull @Past LocalDate dateOfBirth
) {}
