package com.healthplatform.billing.service;

import com.healthplatform.billing.domain.Invoice;
import com.healthplatform.billing.domain.Payment;
import com.healthplatform.billing.event.BillingEventPublisher;
import com.healthplatform.billing.repository.InvoiceRepository;
import com.healthplatform.billing.web.dto.InvoiceResponse;
import com.healthplatform.billing.web.dto.PaymentRequest;
import com.healthplatform.common.events.PaymentCompletedEvent;
import com.healthplatform.common.exception.BusinessException;
import com.healthplatform.common.exception.ResourceNotFoundException;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final BillingEventPublisher eventPublisher;

    public InvoiceService(InvoiceRepository invoiceRepository, BillingEventPublisher eventPublisher) {
        this.invoiceRepository = invoiceRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public InvoiceResponse getById(UUID id) {
        return toResponse(getOrThrow(id));
    }

    @Transactional
    public InvoiceResponse pay(UUID invoiceId, PaymentRequest request) {
        Invoice invoice = getOrThrow(invoiceId);

        BigDecimal alreadyPaid = invoice.getPayments().stream()
                .map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalAfterThisPayment = alreadyPaid.add(request.amount());

        if (totalAfterThisPayment.compareTo(invoice.getAmount()) > 0) {
            throw new BusinessException("OVERPAYMENT", "Payment would exceed the invoice amount");
        }

        Payment payment = new Payment(request.amount(), request.paymentMethod());
        invoice.addPayment(payment);

        if (totalAfterThisPayment.compareTo(invoice.getAmount()) == 0) {
            invoice.markPaid();
        }

        eventPublisher.publishPaymentCompleted(
                payment.getId() != null ? payment.getId().toString() : UUID.randomUUID().toString(),
                new PaymentCompletedEvent(UUID.randomUUID().toString(), invoice.getId().toString(),
                        request.amount(), request.paymentMethod()),
                MDC.get("correlationId")
        );

        return toResponse(invoice);
    }

    private Invoice getOrThrow(UUID id) {
        return invoiceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + id));
    }

    private InvoiceResponse toResponse(Invoice i) {
        return new InvoiceResponse(i.getId(), i.getPatientId(), i.getAppointmentId(), i.getAmount(),
                i.getCurrency(), i.getStatus(), i.getDueDate());
    }
}
