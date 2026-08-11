package com.healthplatform.notification.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthplatform.notification.config.RabbitMqConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for the Kafka -> RabbitMQ bridge. No Spring context, no broker: RabbitTemplate is
 * mocked so we can assert on exactly what gets forwarded to the job queue.
 */
class NotificationRequestConsumerTest {

    private RabbitTemplate rabbitTemplate;
    private NotificationRequestConsumer consumer;

    @BeforeEach
    void setUp() {
        rabbitTemplate = mock(RabbitTemplate.class);
        consumer = new NotificationRequestConsumer(rabbitTemplate, new ObjectMapper());
    }

    @Test
    void translatesEnvelopedKafkaEventIntoRabbitPayload() throws Exception {
        String rawEvent = """
                {
                  "eventId": "8f14e45f-ceea-4d8b-b7c2-6b1e9a6b1a1a",
                  "aggregateId": "patient-123",
                  "eventType": "NOTIFICATION_REQUESTED",
                  "occurredAt": "2026-08-11T10:15:30Z",
                  "version": 1,
                  "correlationId": "corr-456",
                  "payload": {
                    "recipientId": "patient-123",
                    "channel": "EMAIL",
                    "templateCode": "APPT_CONFIRMED",
                    "payloadJson": "{\\"appointmentId\\":\\"appt-1\\"}"
                  }
                }
                """;

        consumer.onNotificationRequested(rawEvent);

        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMqConfig.EXCHANGE),
                eq(RabbitMqConfig.QUEUE),
                any(com.healthplatform.common.events.NotificationRequestedEvent.class));
    }

    @Test
    void forwardsExactPayloadFieldsUnchanged() throws Exception {
        String rawEvent = """
                {
                  "payload": {
                    "recipientId": "patient-999",
                    "channel": "SMS",
                    "templateCode": "LAB_RESULTS_READY",
                    "payloadJson": "{}"
                  }
                }
                """;

        consumer.onNotificationRequested(rawEvent);

        var captor = org.mockito.ArgumentCaptor.forClass(com.healthplatform.common.events.NotificationRequestedEvent.class);
        verify(rabbitTemplate).convertAndSend(eq(RabbitMqConfig.EXCHANGE), eq(RabbitMqConfig.QUEUE), captor.capture());

        var forwarded = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(forwarded.recipientId()).isEqualTo("patient-999");
        org.assertj.core.api.Assertions.assertThat(forwarded.channel()).isEqualTo("SMS");
        org.assertj.core.api.Assertions.assertThat(forwarded.templateCode()).isEqualTo("LAB_RESULTS_READY");
    }

    @Test
    void malformedJsonPropagatesInsteadOfBeingSwallowed() {
        String rawEvent = "{ not valid json ";

        assertThatThrownBy(() -> consumer.onNotificationRequested(rawEvent))
                .isInstanceOf(Exception.class);

        verifyNoInteractions(rabbitTemplate);
    }

}
