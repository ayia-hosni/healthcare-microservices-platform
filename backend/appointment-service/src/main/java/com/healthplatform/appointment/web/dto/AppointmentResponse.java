package com.healthplatform.appointment.web.dto;

import com.healthplatform.appointment.domain.AppointmentStatus;

import java.time.Instant;
import java.util.UUID;

public record AppointmentResponse(
        UUID id,
        UUID patientId,
        UUID doctorId,
        Instant scheduledStart,
        Instant scheduledEnd,
        AppointmentStatus status
) {}
