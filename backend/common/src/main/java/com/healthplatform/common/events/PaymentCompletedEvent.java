package com.healthplatform.common.events;

import java.math.BigDecimal;

/** Payload for the event published when a payment against an invoice is completed. */
public record PaymentCompletedEvent(
        String paymentId,
        String invoiceId,
        BigDecimal amount,
        String paymentMethod
) {}
