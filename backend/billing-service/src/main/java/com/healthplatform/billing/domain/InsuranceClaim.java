package com.healthplatform.billing.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "insurance_claims")
public class InsuranceClaim {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private UUID invoiceId;

    @Column(nullable = false)
    private String insuranceProvider;

    @Column(nullable = false)
    private String status = "SUBMITTED"; // SUBMITTED | APPROVED | DENIED

    @Column(nullable = false)
    private Instant submittedAt = Instant.now();

    protected InsuranceClaim() {}

    public InsuranceClaim(UUID invoiceId, String insuranceProvider) {
        this.invoiceId = invoiceId;
        this.insuranceProvider = insuranceProvider;
    }

    public UUID getId() { return id; }
    public UUID getInvoiceId() { return invoiceId; }
    public String getInsuranceProvider() { return insuranceProvider; }
    public String getStatus() { return status; }
    public void approve() { this.status = "APPROVED"; }
    public void deny() { this.status = "DENIED"; }
}
