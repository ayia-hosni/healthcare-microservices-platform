package com.healthplatform.common.outbox;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A domain event queued for Kafka delivery, written in the SAME transaction as the business
 * change that caused it (see OutboxWriter). This is what closes the dual-write gap described in
 * ADR-0002: publishing directly from a service method means a crash between the DB commit and
 * the Kafka send silently drops the event; writing this row instead means the event either
 * commits with the business change or doesn't exist at all, and OutboxRelay ships it from here
 * on its own schedule, independent of whether the original request's process is still alive.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String topic;

    @Column(nullable = false)
    private String messageKey;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private Instant sentAt;

    protected OutboxEvent() {}

    public OutboxEvent(String topic, String messageKey, String payload) {
        this.topic = topic;
        this.messageKey = messageKey;
        this.payload = payload;
    }

    public void markSent() {
        this.sentAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getTopic() { return topic; }
    public String getMessageKey() { return messageKey; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSentAt() { return sentAt; }
}
