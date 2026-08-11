package com.healthplatform.common.events;

import java.math.BigDecimal;

/** Payload for the event published when billing generates an invoice. */
public record InvoiceGeneratedEvent(
        String invoiceId,
        String patientId,
        String appointmentId,
        BigDecimal amount,
        String currency
) {}
