package com.healthplatform.billing.web.dto;

import com.healthplatform.billing.domain.InvoiceStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record InvoiceResponse(
        UUID id,
        UUID patientId,
        UUID appointmentId,
        BigDecimal amount,
        String currency,
        InvoiceStatus status,
        Instant dueDate
) {}
