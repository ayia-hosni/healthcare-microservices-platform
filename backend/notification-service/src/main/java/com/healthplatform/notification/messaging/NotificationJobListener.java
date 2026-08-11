package com.healthplatform.notification.messaging;

import com.healthplatform.common.events.NotificationRequestedEvent;
import com.healthplatform.notification.config.RabbitMqConfig;
import com.healthplatform.notification.domain.NotificationLog;
import com.healthplatform.notification.repository.NotificationLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Actually "sends" the notification. In this learning build the send is simulated (logged);
 * swap sendEmail/sendSms/sendPush for real provider SDK calls (SES, Twilio, FCM) without
 * touching the retry/DLQ plumbing in RabbitMqConfig.
 */
@Component
public class NotificationJobListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationJobListener.class);

    private final NotificationLogRepository notificationLogRepository;

    public NotificationJobListener(NotificationLogRepository notificationLogRepository) {
        this.notificationLogRepository = notificationLogRepository;
    }

    @RabbitListener(queues = RabbitMqConfig.QUEUE)
    public void handle(NotificationRequestedEvent event) {
        try {
            switch (event.channel()) {
                case "EMAIL" -> sendEmail(event);
                case "SMS" -> sendSms(event);
                case "PUSH" -> sendPush(event);
                default -> throw new IllegalArgumentException("Unknown channel: " + event.channel());
            }
            notificationLogRepository.save(new NotificationLog(event.recipientId(), event.channel(), event.templateCode(), "SENT"));
        } catch (Exception e) {
            notificationLogRepository.save(new NotificationLog(event.recipientId(), event.channel(), event.templateCode(), "FAILED"));
            throw e; // rethrow so the message is nacked and dead-lettered into the retry queue
        }
    }

    private void sendEmail(NotificationRequestedEvent event) {
        log.info("Sending EMAIL to {} using template {}: {}", event.recipientId(), event.templateCode(), event.payloadJson());
    }

    private void sendSms(NotificationRequestedEvent event) {
        log.info("Sending SMS to {} using template {}: {}", event.recipientId(), event.templateCode(), event.payloadJson());
    }

    private void sendPush(NotificationRequestedEvent event) {
        log.info("Sending PUSH to {} using template {}: {}", event.recipientId(), event.templateCode(), event.payloadJson());
    }
}
