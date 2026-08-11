package com.healthplatform.billing.event;

import com.healthplatform.common.events.DomainEvent;
import com.healthplatform.common.events.InvoiceGeneratedEvent;
import com.healthplatform.common.events.PaymentCompletedEvent;
import com.healthplatform.common.events.Topics;
import com.healthplatform.common.outbox.OutboxWriter;
import org.springframework.stereotype.Component;

@Component
public class BillingEventPublisher {

    private final OutboxWriter outboxWriter;

    public BillingEventPublisher(OutboxWriter outboxWriter) {
        this.outboxWriter = outboxWriter;
    }

    public void publishInvoiceGenerated(String invoiceId, InvoiceGeneratedEvent payload, String correlationId) {
        outboxWriter.enqueue(Topics.BILLING_EVENTS, invoiceId,
                DomainEvent.of(invoiceId, "InvoiceGeneratedEvent", correlationId, payload));
    }

    public void publishPaymentCompleted(String paymentId, PaymentCompletedEvent payload, String correlationId) {
        outboxWriter.enqueue(Topics.BILLING_EVENTS, paymentId,
                DomainEvent.of(paymentId, "PaymentCompletedEvent", correlationId, payload));
    }
}
