package com.healthplatform.audit.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Audit records are append-only (no update/delete methods exposed) and store the raw event
 * payload as JSON — this service doesn't need to understand every event's schema, only
 * capture it verbatim, which is what makes it decoupled from every producer's domain model.
 */
@Entity
@Table(name = "audit_records")
public class AuditRecord {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false)
    private String aggregateId;

    @Column(nullable = false)
    private String sourceTopic;

    private String correlationId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String payloadJson;

    @Column(nullable = false)
    private Instant recordedAt = Instant.now();

    protected AuditRecord() {}

    public AuditRecord(String eventType, String aggregateId, String sourceTopic, String correlationId, String payloadJson) {
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.sourceTopic = sourceTopic;
        this.correlationId = correlationId;
        this.payloadJson = payloadJson;
    }

    public UUID getId() { return id; }
    public String getEventType() { return eventType; }
    public String getAggregateId() { return aggregateId; }
    public String getSourceTopic() { return sourceTopic; }
    public String getCorrelationId() { return correlationId; }
    public String getPayloadJson() { return payloadJson; }
    public Instant getRecordedAt() { return recordedAt; }
}
