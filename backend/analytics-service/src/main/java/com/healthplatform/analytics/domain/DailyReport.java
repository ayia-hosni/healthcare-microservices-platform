package com.healthplatform.analytics.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "daily_reports")
public class DailyReport {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private LocalDate reportDate;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String summaryJson;

    @Column(nullable = false)
    private Instant generatedAt = Instant.now();

    protected DailyReport() {}

    public DailyReport(LocalDate reportDate, String summaryJson) {
        this.reportDate = reportDate;
        this.summaryJson = summaryJson;
    }

    public UUID getId() { return id; }
    public LocalDate getReportDate() { return reportDate; }
    public String getSummaryJson() { return summaryJson; }
    public Instant getGeneratedAt() { return generatedAt; }
}
