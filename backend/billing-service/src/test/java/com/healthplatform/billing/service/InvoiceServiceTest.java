package com.healthplatform.billing.service;

import com.healthplatform.billing.domain.Invoice;
import com.healthplatform.billing.domain.InvoiceStatus;
import com.healthplatform.billing.event.BillingEventPublisher;
import com.healthplatform.billing.repository.InvoiceRepository;
import com.healthplatform.billing.web.dto.InvoiceResponse;
import com.healthplatform.billing.web.dto.PaymentRequest;
import com.healthplatform.common.exception.BusinessException;
import com.healthplatform.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InvoiceServiceTest {

    private InvoiceRepository invoiceRepository;
    private BillingEventPublisher eventPublisher;
    private InvoiceService invoiceService;

    @BeforeEach
    void setUp() {
        invoiceRepository = mock(InvoiceRepository.class);
        eventPublisher = mock(BillingEventPublisher.class);
        invoiceService = new InvoiceService(invoiceRepository, eventPublisher);
    }

    // InvoiceService.pay() assumes invoice.getId() is non-null, which always holds for an entity
    // loaded via invoiceRepository.findById() in real usage (JPA assigns the id on persist before
    // it can ever be read back). A bare `new Invoice(...)` in a unit test has no id yet, so this
    // mirrors "loaded from the database" by assigning one via reflection, same as JPA would have.
    private Invoice invoiceOf(BigDecimal amount) {
        Invoice invoice = new Invoice(UUID.randomUUID(), UUID.randomUUID(), amount, Instant.now().plus(30, ChronoUnit.DAYS));
        try {
            Field idField = Invoice.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(invoice, UUID.randomUUID());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return invoice;
    }

    @Test
    void getByIdReturnsInvoiceWhenPresent() {
        UUID id = UUID.randomUUID();
        Invoice invoice = invoiceOf(new BigDecimal("150.00"));
        when(invoiceRepository.findById(id)).thenReturn(Optional.of(invoice));

        InvoiceResponse response = invoiceService.getById(id);

        assertThat(response.amount()).isEqualByComparingTo("150.00");
        assertThat(response.status()).isEqualTo(InvoiceStatus.PENDING);
    }

    @Test
    void getByIdThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(invoiceRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> invoiceService.getById(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    @Test
    void payPartialAmountLeavesInvoicePending() {
        UUID id = UUID.randomUUID();
        Invoice invoice = invoiceOf(new BigDecimal("150.00"));
        when(invoiceRepository.findById(id)).thenReturn(Optional.of(invoice));

        InvoiceResponse response = invoiceService.pay(id, new PaymentRequest(new BigDecimal("50.00"), "CREDIT_CARD"));

        assertThat(response.status()).isEqualTo(InvoiceStatus.PENDING);
        assertThat(invoice.getPayments()).hasSize(1);
        verify(eventPublisher).publishPaymentCompleted(any(), any(), any());
    }

    @Test
    void payExactRemainingAmountMarksInvoicePaid() {
        UUID id = UUID.randomUUID();
        Invoice invoice = invoiceOf(new BigDecimal("150.00"));
        when(invoiceRepository.findById(id)).thenReturn(Optional.of(invoice));

        InvoiceResponse response = invoiceService.pay(id, new PaymentRequest(new BigDecimal("150.00"), "CREDIT_CARD"));

        assertThat(response.status()).isEqualTo(InvoiceStatus.PAID);
    }

    @Test
    void payAcrossMultiplePaymentsMarksInvoicePaidWhenFullyCovered() {
        UUID id = UUID.randomUUID();
        Invoice invoice = invoiceOf(new BigDecimal("150.00"));
        when(invoiceRepository.findById(id)).thenReturn(Optional.of(invoice));

        invoiceService.pay(id, new PaymentRequest(new BigDecimal("100.00"), "CREDIT_CARD"));
        InvoiceResponse response = invoiceService.pay(id, new PaymentRequest(new BigDecimal("50.00"), "CASH"));

        assertThat(response.status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(invoice.getPayments()).hasSize(2);
    }

    @Test
    void payRejectsAmountExceedingInvoiceTotal() {
        UUID id = UUID.randomUUID();
        Invoice invoice = invoiceOf(new BigDecimal("150.00"));
        when(invoiceRepository.findById(id)).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> invoiceService.pay(id, new PaymentRequest(new BigDecimal("200.00"), "CREDIT_CARD")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("exceed");

        assertThat(invoice.getPayments()).isEmpty();
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void payRejectsOverpaymentAcrossMultiplePayments() {
        UUID id = UUID.randomUUID();
        Invoice invoice = invoiceOf(new BigDecimal("150.00"));
        when(invoiceRepository.findById(id)).thenReturn(Optional.of(invoice));

        invoiceService.pay(id, new PaymentRequest(new BigDecimal("100.00"), "CREDIT_CARD"));

        assertThatThrownBy(() -> invoiceService.pay(id, new PaymentRequest(new BigDecimal("100.00"), "CASH")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("exceed");

        assertThat(invoice.getPayments()).hasSize(1);
    }

    @Test
    void payThrowsWhenInvoiceMissing() {
        UUID id = UUID.randomUUID();
        when(invoiceRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> invoiceService.pay(id, new PaymentRequest(new BigDecimal("50.00"), "CASH")))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(eventPublisher);
    }
}
