package com.healthplatform.appointment.web.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record JoinWaitingListRequest(
        @NotNull UUID patientId,
        @NotNull UUID doctorId
) {}
