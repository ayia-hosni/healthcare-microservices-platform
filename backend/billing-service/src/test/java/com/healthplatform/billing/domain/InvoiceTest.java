package com.healthplatform.billing.domain;

import com.healthplatform.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvoiceTest {

    private Invoice newInvoice() {
        return new Invoice(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("150.00"),
                Instant.now().plus(30, ChronoUnit.DAYS));
    }

    @Test
    void newInvoiceStartsPending() {
        Invoice invoice = newInvoice();
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PENDING);
    }

    @Test
    void markPaidTransitionsToPaid() {
        Invoice invoice = newInvoice();
        invoice.markPaid();
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
    }

    @Test
    void markOverdueTransitionsFromPending() {
        Invoice invoice = newInvoice();
        invoice.markOverdue();
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.OVERDUE);
    }

    @Test
    void markOverdueIsNoOpWhenAlreadyPaid() {
        Invoice invoice = newInvoice();
        invoice.markPaid();
        invoice.markOverdue();
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
    }

    @Test
    void markOverdueIsNoOpWhenAlreadyCancelled() {
        Invoice invoice = newInvoice();
        invoice.cancel();
        invoice.markOverdue();
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.CANCELLED);
    }

    @Test
    void cancelTransitionsPendingToCancelled() {
        Invoice invoice = newInvoice();
        invoice.cancel();
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.CANCELLED);
    }

    @Test
    void cancelRejectsAlreadyPaidInvoice() {
        Invoice invoice = newInvoice();
        invoice.markPaid();

        assertThatThrownBy(invoice::cancel)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Cannot cancel a paid invoice");

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
    }

    @Test
    void addPaymentLinksPaymentBackToInvoice() {
        Invoice invoice = newInvoice();
        Payment payment = new Payment(new BigDecimal("150.00"), "CREDIT_CARD");

        invoice.addPayment(payment);

        assertThat(invoice.getPayments()).containsExactly(payment);
    }
}
