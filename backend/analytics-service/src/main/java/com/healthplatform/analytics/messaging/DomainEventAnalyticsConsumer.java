package com.healthplatform.analytics.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthplatform.analytics.repository.EventCounterRepository;
import com.healthplatform.common.events.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Component
public class DomainEventAnalyticsConsumer {

    private static final Logger log = LoggerFactory.getLogger(DomainEventAnalyticsConsumer.class);

    private final EventCounterRepository eventCounterRepository;
    private final ObjectMapper objectMapper;

    public DomainEventAnalyticsConsumer(EventCounterRepository eventCounterRepository, ObjectMapper objectMapper) {
        this.eventCounterRepository = eventCounterRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = {
            Topics.PATIENT_EVENTS, Topics.DOCTOR_EVENTS, Topics.APPOINTMENT_EVENTS,
            Topics.EMR_EVENTS, Topics.BILLING_EVENTS
    }, groupId = "analytics-service")
    @Transactional
    public void onDomainEvent(String rawEvent) {
        try {
            JsonNode node = objectMapper.readTree(rawEvent);
            String eventType = node.path("eventType").asText("UNKNOWN");
            eventCounterRepository.incrementCount(eventType, LocalDate.now());
        } catch (Exception e) {
            log.error("Failed to record analytics counter: {}", e.getMessage(), e);
            // Rethrow so the container's error handler (see KafkaDlqConfig) can retry and,
            // on repeated failure, dead-letter the record instead of silently dropping it.
            throw new RuntimeException("Failed to record analytics counter", e);
        }
    }
}
