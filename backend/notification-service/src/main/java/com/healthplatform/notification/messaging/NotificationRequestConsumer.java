package com.healthplatform.notification.messaging;

import com.healthplatform.common.events.NotificationRequestedEvent;
import com.healthplatform.common.events.Topics;
import com.healthplatform.notification.config.RabbitMqConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Bridges Kafka (where every service publishes domain events) to RabbitMQ (which owns the
 * actual job-queue semantics — retry, DLQ, per-worker prefetch — for notification delivery).
 * Kafka is the event backbone; RabbitMQ is the task queue. Using both isn't redundant: Kafka
 * events are broadcast/replayable, RabbitMQ messages are consumed-once work items.
 */
@Component
public class NotificationRequestConsumer {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public NotificationRequestConsumer(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = Topics.NOTIFICATION_REQUESTS, groupId = "notification-service")
    public void onNotificationRequested(String rawEvent) throws Exception {
        JsonNode node = objectMapper.readTree(rawEvent);
        JsonNode payloadNode = node.path("payload");

        NotificationRequestedEvent payload = objectMapper.treeToValue(payloadNode, NotificationRequestedEvent.class);
        rabbitTemplate.convertAndSend(RabbitMqConfig.EXCHANGE, RabbitMqConfig.QUEUE, payload);
    }
}
