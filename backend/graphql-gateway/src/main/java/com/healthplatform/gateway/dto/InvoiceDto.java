package com.healthplatform.gateway.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Mirrors billing-service's InvoiceResponse. dueDate is left as a raw ISO-8601 String (not
 * parsed into Instant) — see PatientDto's dateOfBirth for why. amount stays BigDecimal:
 * graphql-java's default Float scalar coercion handles Number subtypes directly.
 */
public record InvoiceDto(
        UUID id,
        UUID patientId,
        UUID appointmentId,
        BigDecimal amount,
        String currency,
        InvoiceStatus status,
        String dueDate
) {
    public enum InvoiceStatus {
        PENDING, PAID, OVERDUE, CANCELLED
    }
}
