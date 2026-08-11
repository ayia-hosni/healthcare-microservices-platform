package com.healthplatform.billing.repository;

import com.healthplatform.billing.domain.Invoice;
import com.healthplatform.billing.domain.InvoiceStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {
    List<Invoice> findAllByStatusAndDueDateBefore(InvoiceStatus status, Instant cutoff);
    List<Invoice> findAllByAppointmentId(UUID appointmentId);
}
