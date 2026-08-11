package com.healthplatform.appointment.web.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record RescheduleRequest(
        @NotNull @Future Instant newStart,
        @NotNull @Future Instant newEnd
) {}
