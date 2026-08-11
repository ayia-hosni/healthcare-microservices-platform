package com.healthplatform.appointment.web.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public record BookAppointmentRequest(
        @NotNull UUID patientId,
        @NotNull UUID doctorId,
        @NotNull @Future Instant scheduledStart,
        @NotNull @Future Instant scheduledEnd,
        @NotBlank String idempotencyKey
) {}
