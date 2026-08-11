package com.healthplatform.notification;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirms the Spring context actually starts cleanly against a real Postgres with Flyway
 * migrations applied. This matters specifically for notification-service: it just started
 * picking up common.security.PlatformSecurityConfig via a widened @ComponentScan (see
 * NotificationServiceApplication's Javadoc) even though it has spring-security transitively on
 * its classpath via `common` and never had ANY SecurityConfig of its own before. If that wiring
 * were broken, this test would fail to even boot.
 *
 * Kafka and RabbitMQ are NOT containerized here (per the test setup for this service) — the
 * Kafka SASL placeholders and Rabbit credentials that have no defaults in application.yml are
 * satisfied with dummy values purely so property binding succeeds, and both listener containers
 * are disabled via auto-startup=false so this test never actually needs a broker. The
 * @KafkaListener / @RabbitListener paths are covered separately by unit tests that mock
 * RabbitTemplate / the repository.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class NotificationServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("notification_db")
            .withUsername("notification_user")
            .withPassword("notification_pass");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        // No broker containerized: keep Kafka/Rabbit listener containers from trying to
        // connect, and satisfy the no-default placeholders in application.yml so property
        // binding of spring.kafka.properties / spring.rabbitmq.* doesn't fail at startup.
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("KAFKA_SASL_USERNAME", () -> "test-user");
        registry.add("KAFKA_SASL_PASSWORD", () -> "test-pass");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
        registry.add("RABBITMQ_USER", () -> "test-rabbit-user");
        registry.add("RABBITMQ_PASSWORD", () -> "test-rabbit-pass");
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void contextStartsAndActuatorHealthIsPubliclyReachable() {
        // /actuator/health must stay permitAll under the newly-inherited PlatformSecurityConfig
        // (see NotificationServiceApplication's Javadoc: it would otherwise fall back to Spring
        // Boot's default HTTP Basic auth with a random per-boot password, locking out
        // Prometheus and the Docker HEALTHCHECK).
        var response = restTemplate.getForEntity("http://localhost:" + port + "/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
