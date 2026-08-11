package com.healthplatform.common.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthplatform.common.events.DomainEvent;
import org.springframework.stereotype.Component;

/**
 * Drop-in replacement for calling KafkaTemplate.send(...) directly from a service method: same
 * call shape (topic, key, event), but this write participates in the caller's existing
 * @Transactional instead of firing an independent network call. OutboxRelay ships it afterward.
 */
@Component
public class OutboxWriter {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void enqueue(String topic, String key, DomainEvent<?> event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            repository.save(new OutboxEvent(topic, key, payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox event for topic " + topic, e);
        }
    }
}
