package com.healthplatform.billing.scheduler;

import com.healthplatform.billing.domain.Invoice;
import com.healthplatform.billing.domain.InvoiceStatus;
import com.healthplatform.billing.repository.InvoiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Covers the "cancel unpaid appointments" requirement from the appointment-service's
 * perspective: this job flags PENDING invoices past their due date as OVERDUE. It does not
 * reach across services to cancel the appointment directly — instead (in the full design)
 * it would publish an event that appointment-service consumes, keeping each service the sole
 * writer of its own data (no service ever mutates another service's tables).
 */
@Component
public class OverdueInvoiceJob {

    private static final Logger log = LoggerFactory.getLogger(OverdueInvoiceJob.class);

    private final InvoiceRepository invoiceRepository;

    public OverdueInvoiceJob(InvoiceRepository invoiceRepository) {
        this.invoiceRepository = invoiceRepository;
    }

    @Scheduled(cron = "0 0 2 * * *") // daily at 02:00
    @Transactional
    public void markOverdueInvoices() {
        List<Invoice> pastDue = invoiceRepository.findAllByStatusAndDueDateBefore(InvoiceStatus.PENDING, Instant.now());
        pastDue.forEach(Invoice::markOverdue);
        log.info("Marked {} invoices overdue", pastDue.size());
    }
}
