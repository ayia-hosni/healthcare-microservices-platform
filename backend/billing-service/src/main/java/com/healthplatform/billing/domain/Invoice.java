package com.healthplatform.billing.domain;

import com.healthplatform.common.exception.BusinessException;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "invoices")
public class Invoice {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private UUID patientId;

    private UUID appointmentId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency = "USD";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvoiceStatus status = InvoiceStatus.PENDING;

    @Column(nullable = false)
    private Instant dueDate;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Payment> payments = new ArrayList<>();

    @Version
    private Long version;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected Invoice() {}

    public Invoice(UUID patientId, UUID appointmentId, BigDecimal amount, Instant dueDate) {
        this.patientId = patientId;
        this.appointmentId = appointmentId;
        this.amount = amount;
        this.dueDate = dueDate;
    }

    public void markPaid() {
        this.status = InvoiceStatus.PAID;
    }

    public void markOverdue() {
        if (status == InvoiceStatus.PENDING) {
            this.status = InvoiceStatus.OVERDUE;
        }
    }

    public void cancel() {
        if (status == InvoiceStatus.PAID) {
            throw new BusinessException("INVALID_STATE_TRANSITION", "Cannot cancel a paid invoice");
        }
        this.status = InvoiceStatus.CANCELLED;
    }

    public void addPayment(Payment payment) {
        payment.assignTo(this);
        payments.add(payment);
    }

    public UUID getId() { return id; }
    public UUID getPatientId() { return patientId; }
    public UUID getAppointmentId() { return appointmentId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public InvoiceStatus getStatus() { return status; }
    public Instant getDueDate() { return dueDate; }
    public List<Payment> getPayments() { return payments; }
}
