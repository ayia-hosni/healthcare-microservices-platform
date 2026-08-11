package com.healthplatform.analytics.domain;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One row per (eventType, day). Incremented on every matching Kafka message — a simple
 * rollup table that keeps read queries for dashboards O(1) instead of scanning raw events.
 */
@Entity
@Table(name = "event_counters", uniqueConstraints = @UniqueConstraint(columnNames = {"event_type", "counter_date"}))
public class EventCounter {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "counter_date", nullable = false)
    private LocalDate counterDate;

    @Column(nullable = false)
    private long count = 0;

    protected EventCounter() {}

    public EventCounter(String eventType, LocalDate counterDate) {
        this.eventType = eventType;
        this.counterDate = counterDate;
    }

    public void increment() { this.count++; }

    public UUID getId() { return id; }
    public String getEventType() { return eventType; }
    public LocalDate getCounterDate() { return counterDate; }
    public long getCount() { return count; }
}
