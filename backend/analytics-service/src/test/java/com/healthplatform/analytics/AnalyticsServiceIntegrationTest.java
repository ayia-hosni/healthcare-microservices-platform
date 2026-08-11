package com.healthplatform.analytics;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirms the Spring context starts cleanly against a real Postgres with Flyway migrations
 * applied, and that the GET endpoint is protected by the newly-inherited
 * common.security.PlatformSecurityConfig — analytics-service, like audit-service, had no
 * SecurityConfig of its own before the widened @ComponentScan in AnalyticsServiceApplication.
 *
 * Quartz's JDBC job store (spring.quartz.job-store-type=jdbc in application.yml) expects the
 * QRTZ_* tables to already exist, provisioned externally at infra time (see the comment in
 * db/migration/V1__init.sql and application.yml's initialize-schema: never) — they are
 * deliberately NOT part of this service's Flyway migrations. A fresh Testcontainers Postgres
 * has none of that infra, so this test overrides Quartz to an in-memory job store; that's a
 * test-environment concern only; it doesn't change production wiring.
 *
 * Kafka is NOT containerized here for the same reasons as the other two services' integration
 * tests; the @KafkaListener path is covered by DomainEventAnalyticsConsumerTest (unit, mocked
 * repository).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AnalyticsServiceIntegrationTest {

    private static final byte[] JWT_SECRET =
            "change-me-in-production-this-must-be-at-least-32-bytes-long".getBytes();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("analytics_db")
            .withUsername("analytics_user")
            .withPassword("analytics_pass");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("KAFKA_SASL_USERNAME", () -> "test-user");
        registry.add("KAFKA_SASL_PASSWORD", () -> "test-pass");

        // See class Javadoc: QRTZ_* tables aren't provisioned in this test's fresh database.
        registry.add("spring.quartz.job-store-type", () -> "memory");
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    private String jwtFor(String username, String... roles) {
        var key = Keys.hmacShaKeyFor(JWT_SECRET);
        return Jwts.builder().subject(username).claim("roles", List.of(roles)).signWith(key).compact();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void actuatorHealthIsPubliclyReachable() {
        var response = restTemplate.getForEntity(url("/actuator/health"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void countersWithoutTokenIsUnauthorized() {
        var response = restTemplate.getForEntity(url("/api/v1/analytics/counters?date=2026-08-10"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void countersWithAdminTokenReturnsOkAndEmptyListWhenNoDataExists() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtFor("admin-user", "ROLE_ADMIN"));

        var response = restTemplate.exchange(
                url("/api/v1/analytics/counters?date=2026-08-10"),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("[]");
    }

    @Test
    void countersWithNonAdminTokenIsForbidden() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtFor("doctor-user", "ROLE_DOCTOR"));

        var response = restTemplate.exchange(
                url("/api/v1/analytics/counters?date=2026-08-10"),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
