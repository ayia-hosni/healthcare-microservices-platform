package com.healthplatform.analytics.repository;

import com.healthplatform.analytics.domain.EventCounter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EventCounterRepository extends JpaRepository<EventCounter, UUID> {
    Optional<EventCounter> findByEventTypeAndCounterDate(String eventType, LocalDate counterDate);
    List<EventCounter> findAllByCounterDate(LocalDate counterDate);

    /**
     * Atomic upsert-increment: safe against concurrent Kafka consumer threads and against
     * at-least-once redelivery racing on the same (eventType, day) row, which a naive
     * read-then-save in application code would not be.
     */
    @Modifying
    @Query(value = """
            INSERT INTO event_counters (id, event_type, counter_date, count)
            VALUES (gen_random_uuid(), :eventType, :counterDate, 1)
            ON CONFLICT (event_type, counter_date)
            DO UPDATE SET count = event_counters.count + 1
            """, nativeQuery = true)
    void incrementCount(String eventType, LocalDate counterDate);
}
