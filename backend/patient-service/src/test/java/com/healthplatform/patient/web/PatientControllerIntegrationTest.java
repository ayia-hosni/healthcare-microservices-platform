package com.healthplatform.patient.web;

import com.healthplatform.common.outbox.OutboxEventRepository;
import com.healthplatform.patient.web.dto.PatientRequest;
import com.healthplatform.patient.web.dto.PatientResponse;
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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof that PatientController + PatientService + Flyway migrations + JPA mappings +
 * the shared PlatformSecurityConfig JWT chain (from `common`) all wire up correctly against a
 * real Postgres instance.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PatientControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("patient_db")
            .withUsername("patient_user")
            .withPassword("patient_pass");

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // No Redis broker in this test; skip real caching so the context doesn't need one.
        registry.add("spring.cache.type", () -> "none");
        // Avoid resolving SASL creds that aren't set in this environment; PLAINTEXT is enough
        // since OutboxRelay's Kafka send failures are caught and retried, never fatal to startup.
        registry.add("spring.kafka.properties.security.protocol", () -> "PLAINTEXT");
        registry.add("spring.kafka.properties.sasl.mechanism", () -> "");
        registry.add("spring.kafka.properties.sasl.jaas.config", () -> "");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private String jwtFor(String username, String... roles) {
        var key = Keys.hmacShaKeyFor("change-me-in-production-this-must-be-at-least-32-bytes-long".getBytes());
        return Jwts.builder().subject(username).claim("roles", List.of(roles)).signWith(key).compact();
    }

    private HttpHeaders authHeaders(String... roles) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtFor("aya", roles));
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return headers;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void registerThenFetchRoundTripsThroughRealDatabase() {
        UUID id = UUID.randomUUID();
        PatientRequest request = new PatientRequest(id, "Aya", "Hosni",
                LocalDate.of(1990, 5, 1), "aya@example.com", "555-1234");

        ResponseEntity<PatientResponse> createResponse = restTemplate.exchange(
                url("/api/v1/patients"), HttpMethod.POST,
                new HttpEntity<>(request, authHeaders("ROLE_ADMIN")), PatientResponse.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createResponse.getBody()).isNotNull();
        assertThat(createResponse.getBody().id()).isEqualTo(id);

        ResponseEntity<PatientResponse> getResponse = restTemplate.exchange(
                url("/api/v1/patients/" + id), HttpMethod.GET,
                new HttpEntity<>(authHeaders("ROLE_DOCTOR")), PatientResponse.class);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody()).isNotNull();
        assertThat(getResponse.getBody().firstName()).isEqualTo("Aya");
        assertThat(getResponse.getBody().email()).isEqualTo("aya@example.com");

        // The outbox write (ADR-0002) committed atomically with the patient insert.
        assertThat(outboxEventRepository.findAll())
                .anyMatch(event -> event.getMessageKey().equals(id.toString()));
    }

    @Test
    void registerWithoutBearerTokenIsRejected() {
        UUID id = UUID.randomUUID();
        PatientRequest request = new PatientRequest(id, "Amina", "Nasser",
                LocalDate.of(1988, 2, 2), "amina@example.com", "555-5678");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/v1/patients"), HttpMethod.POST, new HttpEntity<>(request, headers), String.class);

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.CREATED);
    }

    @Test
    void getUnknownPatientReturnsNotFound() {
        UUID missingId = UUID.randomUUID();

        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/v1/patients/" + missingId), HttpMethod.GET,
                new HttpEntity<>(authHeaders("ROLE_ADMIN")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
