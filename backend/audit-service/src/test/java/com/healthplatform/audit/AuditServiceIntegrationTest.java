package com.healthplatform.audit;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
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
 * applied, and that the GET endpoint is actually protected by the newly-inherited
 * common.security.PlatformSecurityConfig. Before the widened @ComponentScan in
 * AuditServiceApplication, audit-service had no SecurityConfig of its own at all; this is what
 * proves that consolidation didn't silently leave the endpoint open (or, conversely, break
 * startup entirely).
 *
 * Kafka is NOT containerized here — the SASL placeholders with no defaults in application.yml
 * are satisfied with dummy values purely so property binding succeeds, and the listener
 * containers are disabled via auto-startup=false. The @KafkaListener path itself is covered by
 * DomainEventAuditConsumerTest (unit, mocked repository).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AuditServiceIntegrationTest {

    private static final byte[] JWT_SECRET =
            "change-me-in-production-this-must-be-at-least-32-bytes-long".getBytes();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("audit_db")
            .withUsername("audit_user")
            .withPassword("audit_pass");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("KAFKA_SASL_USERNAME", () -> "test-user");
        registry.add("KAFKA_SASL_PASSWORD", () -> "test-pass");
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
    void byAggregateWithoutTokenIsUnauthorized() {
        var response = restTemplate.getForEntity(url("/api/v1/audit/by-aggregate/patient-123"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void byAggregateWithAdminTokenReturnsOkAndEmptyListWhenNoRecordsExist() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtFor("admin-user", "ROLE_ADMIN"));

        var response = restTemplate.exchange(
                url("/api/v1/audit/by-aggregate/patient-does-not-exist"),
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("[]");
    }

    @Test
    void byAggregateWithNonAdminTokenIsForbidden() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtFor("patient-user", "ROLE_PATIENT"));

        var response = restTemplate.exchange(
                url("/api/v1/audit/by-aggregate/patient-123"),
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
