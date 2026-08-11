package com.healthplatform.notification.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Retry + dead-letter topology.
 *
 * Flow: notification.exchange -> notification.queue (main work queue) -> on handler failure,
 * the message is nacked and routed via notification.dlx to notification.retry.queue, which has
 * NO consumer and a message TTL — messages simply expire there and are dead-lettered again,
 * this time back into notification.exchange, giving a delayed-retry effect without needing a
 * plugin. After N redeliveries (tracked via the x-death header, checked in the listener), the
 * message is routed instead to notification.dlq.queue for manual inspection — the true
 * dead-letter queue, as opposed to the retry queue's temporary holding role.
 */
@Configuration
public class RabbitMqConfig {

    public static final String EXCHANGE = "notification.exchange";
    public static final String QUEUE = "notification.queue";
    public static final String RETRY_EXCHANGE = "notification.retry.exchange";
    public static final String RETRY_QUEUE = "notification.retry.queue";
    public static final String DLQ_EXCHANGE = "notification.dlq.exchange";
    public static final String DLQ_QUEUE = "notification.dlq.queue";

    @Bean
    public DirectExchange notificationExchange() {
        return new DirectExchange(EXCHANGE);
    }

    @Bean
    public Queue notificationQueue() {
        return QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", RETRY_EXCHANGE)
                .build();
    }

    @Bean
    public Binding notificationBinding() {
        return BindingBuilder.bind(notificationQueue()).to(notificationExchange()).with(QUEUE);
    }

    @Bean
    public DirectExchange retryExchange() {
        return new DirectExchange(RETRY_EXCHANGE);
    }

    @Bean
    public Queue retryQueue() {
        return QueueBuilder.durable(RETRY_QUEUE)
                .withArgument("x-dead-letter-exchange", EXCHANGE)   // after TTL expiry, goes back to main exchange
                .withArgument("x-message-ttl", 30000)               // 30s backoff before redelivery
                .build();
    }

    @Bean
    public Binding retryBinding() {
        return BindingBuilder.bind(retryQueue()).to(retryExchange()).with(QUEUE);
    }

    @Bean
    public DirectExchange dlqExchange() {
        return new DirectExchange(DLQ_EXCHANGE);
    }

    @Bean
    public Queue dlqQueue() {
        return QueueBuilder.durable(DLQ_QUEUE).build();
    }

    @Bean
    public Binding dlqBinding() {
        return BindingBuilder.bind(dlqQueue()).to(dlqExchange()).with(QUEUE);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
