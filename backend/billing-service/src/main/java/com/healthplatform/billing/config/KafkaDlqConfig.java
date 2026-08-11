package com.healthplatform.billing.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.Map;

/**
 * Kafka-side DLQ parity with notification-service's RabbitMqConfig: a record that keeps
 * failing is retried a couple of times, then published to "&lt;original-topic&gt;.DLT" instead
 * of being retried forever (default behaviour) or silently dropped (this service's previous
 * catch-and-log-only consumer code). Uses its own String/String producer, independent of
 * whatever serializer BillingEventPublisher's KafkaTemplate&lt;String, Object&gt; uses, so the
 * raw JSON payload lands in the DLT unmodified for replay/inspection.
 */
@Configuration
public class KafkaDlqConfig {

    // Declared as KafkaOperations (not KafkaTemplate) so this bean's raw type doesn't satisfy
    // Spring Boot's @ConditionalOnMissingBean(KafkaTemplate.class) on KafkaAutoConfiguration's
    // own KafkaTemplate<?, ?> bean. That default bean is what OutboxRelay's KafkaTemplate<String,
    // Object> constructor parameter needs (its wildcard type generically matches); a concretely
    // typed KafkaTemplate<Object, Object> here would suppress it and leave OutboxRelay unable to
    // start at all (verified: this broke full Spring context startup before this fix).
    @Bean
    public KafkaOperations<Object, Object> dlqKafkaTemplate(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        Map<String, Object> producerProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class
        );
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProps));
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> dlqKafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(dlqKafkaTemplate);
        // 1 initial attempt + 2 retries, 1s apart, then dead-letter.
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConsumerFactory<Object, Object> consumerFactory,
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            DefaultErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }
}
