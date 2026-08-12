package com.healthplatform.gateway.dto;

import java.util.UUID;

/**
 * Mirrors appointment-service's AppointmentResponse. scheduledStart/scheduledEnd are left as
 * raw ISO-8601 Strings (not parsed into Instant) — see PatientDto's dateOfBirth for why:
 * graphql-java's default String scalar serializer would otherwise null them out.
 */
public record AppointmentDto(
        UUID id,
        UUID patientId,
        UUID doctorId,
        String scheduledStart,
        String scheduledEnd,
        AppointmentStatus status
) {
    public enum AppointmentStatus {
        SCHEDULED, CONFIRMED, CANCELLED, COMPLETED, NO_SHOW
    }
}
