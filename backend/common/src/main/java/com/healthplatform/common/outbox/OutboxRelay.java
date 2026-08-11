package com.healthplatform.common.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Polls for unsent outbox rows and relays them to Kafka. Deliberately synchronous per record
 * (blocks on the send future) so a row is only marked sent once Kafka has actually acked it —
 * an unsent row just gets retried next tick, which is the entire point of the pattern. One
 * failing record logs and is skipped for this pass rather than blocking the rest of the batch.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public OutboxRelay(OutboxEventRepository repository, KafkaTemplate<String, Object> kafkaTemplate,
                        ObjectMapper objectMapper) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void relay() {
        List<OutboxEvent> batch = repository.findTop100BySentAtIsNullOrderByCreatedAtAsc();
        for (OutboxEvent event : batch) {
            try {
                JsonNode payload = objectMapper.readTree(event.getPayload());
                kafkaTemplate.send(event.getTopic(), event.getMessageKey(), payload)
                        .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                event.markSent();
            } catch (Exception e) {
                log.warn("Failed to relay outbox event {} to topic {}, will retry next tick: {}",
                        event.getId(), event.getTopic(), e.getMessage());
            }
        }
    }
}
