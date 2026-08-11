package com.healthplatform.appointment.web.dto;

import jakarta.validation.constraints.NotBlank;

public record CancelRequest(@NotBlank String reason) {}
