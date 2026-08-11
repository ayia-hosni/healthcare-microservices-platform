package com.healthplatform.analytics.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthplatform.analytics.repository.EventCounterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for the counter-increment logic. Repository is mocked so we can assert exactly
 * which (eventType, date) key gets incremented, and that a malformed payload is not silently
 * swallowed (matching this class's own comment about rethrowing for the DLQ error handler).
 */
class DomainEventAnalyticsConsumerTest {

    private EventCounterRepository eventCounterRepository;
    private DomainEventAnalyticsConsumer consumer;

    @BeforeEach
    void setUp() {
        eventCounterRepository = mock(EventCounterRepository.class);
        consumer = new DomainEventAnalyticsConsumer(eventCounterRepository, new ObjectMapper());
    }

    @Test
    void incrementsCounterForExtractedEventTypeOnToday() {
        String rawEvent = "{ \"eventType\": \"APPOINTMENT_CREATED\", \"aggregateId\": \"appt-1\" }";

        consumer.onDomainEvent(rawEvent);

        verify(eventCounterRepository).incrementCount(eq("APPOINTMENT_CREATED"), eq(LocalDate.now()));
    }

    @Test
    void missingEventTypeFallsBackToUnknownRatherThanFailing() {
        String rawEvent = "{ \"aggregateId\": \"appt-2\" }";

        consumer.onDomainEvent(rawEvent);

        verify(eventCounterRepository).incrementCount(eq("UNKNOWN"), eq(LocalDate.now()));
    }

    @Test
    void malformedJsonPropagatesInsteadOfBeingSilentlySwallowed() {
        String rawEvent = "{ not valid json at all";

        assertThatThrownBy(() -> consumer.onDomainEvent(rawEvent))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to record analytics counter");

        verify(eventCounterRepository, never()).incrementCount(any(), any());
    }
}
