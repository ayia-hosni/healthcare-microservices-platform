package com.healthplatform.common.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/**
 * Base envelope for every domain event published to Kafka.
 *
 * Every event carries:
 *  - eventId:       unique identifier for this specific event occurrence (idempotency key on the consumer side)
 *  - aggregateId:   id of the aggregate root the event is about (patientId, appointmentId, etc.)
 *  - eventType:     discriminator used by consumers / the outbox relay
 *  - occurredAt:    when the event was produced
 *  - version:       schema version of the payload, bumped on breaking changes
 *  - correlationId: ties the event back to the originating request/saga across services
 *  - payload:       the event-specific data
 *
 * @param <T> type of the payload
 */
public class DomainEvent<T> {

    private final UUID eventId;
    private final String aggregateId;
    private final String eventType;
    private final Instant occurredAt;
    private final int version;
    private final String correlationId;
    private final T payload;

    @JsonCreator
    public DomainEvent(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("aggregateId") String aggregateId,
            @JsonProperty("eventType") String eventType,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("version") int version,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("payload") T payload) {
        this.eventId = eventId;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.occurredAt = occurredAt;
        this.version = version;
        this.correlationId = correlationId;
        this.payload = payload;
    }

    public static <T> DomainEvent<T> of(String aggregateId, String eventType, String correlationId, T payload) {
        return new DomainEvent<>(UUID.randomUUID(), aggregateId, eventType, Instant.now(), 1, correlationId, payload);
    }

    public UUID getEventId() { return eventId; }
    public String getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public Instant getOccurredAt() { return occurredAt; }
    public int getVersion() { return version; }
    public String getCorrelationId() { return correlationId; }
    public T getPayload() { return payload; }
}
