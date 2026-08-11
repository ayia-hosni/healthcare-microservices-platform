package com.healthplatform.emr.web;

import com.healthplatform.emr.web.dto.DiagnosisRequest;
import com.healthplatform.emr.web.dto.EncounterRequest;
import com.healthplatform.emr.web.dto.EncounterResponse;
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
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of the encounter creation / diagnosis-add flow against a real Postgres
 * container (Flyway-migrated) and the real JWT security chain. Document upload is intentionally
 * NOT exercised here: it needs a reachable MinIO, and there's no Testcontainers MinIO module in
 * this repo.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EncounterIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("emr_db")
            .withUsername("emr_user")
            .withPassword("emr_pass");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Avoid the real Kafka SASL_SSL config (unresolvable truststore path) during tests;
        // the outbox pattern means nothing in the request path depends on a live broker.
        registry.add("spring.kafka.properties.security.protocol", () -> "PLAINTEXT");
        registry.add("spring.kafka.properties.sasl.mechanism", () -> "");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private static final String JWT_SECRET = "change-me-in-production-this-must-be-at-least-32-bytes-long";

    private String jwtFor(String username, String... roles) {
        var key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes());
        return Jwts.builder().subject(username).claim("roles", List.of(roles)).signWith(key).compact();
    }

    private HttpHeaders authHeaders(String... roles) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtFor("doctor.aya@example.com", roles));
        return headers;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void createEncounterThenAddDiagnosisEndToEnd() {
        EncounterRequest createRequest = new EncounterRequest(UUID.randomUUID(), UUID.randomUUID(), null, "Annual physical");
        HttpEntity<EncounterRequest> createEntity = new HttpEntity<>(createRequest, authHeaders("ROLE_DOCTOR"));

        ResponseEntity<EncounterResponse> createResponse = restTemplate.postForEntity(
                url("/api/v1/encounters"), createEntity, EncounterResponse.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createResponse.getBody()).isNotNull();
        UUID encounterId = createResponse.getBody().id();
        assertThat(encounterId).isNotNull();

        DiagnosisRequest diagnosisRequest = new DiagnosisRequest("E11.9", "Type 2 diabetes without complications");
        HttpEntity<DiagnosisRequest> diagnosisEntity = new HttpEntity<>(diagnosisRequest, authHeaders("ROLE_DOCTOR"));

        ResponseEntity<EncounterResponse> diagnosisResponse = restTemplate.postForEntity(
                url("/api/v1/encounters/" + encounterId + "/diagnoses"), diagnosisEntity, EncounterResponse.class);

        assertThat(diagnosisResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(diagnosisResponse.getBody().diagnoses()).containsExactly("E11.9 - Type 2 diabetes without complications");

        ResponseEntity<EncounterResponse> getResponse = restTemplate.exchange(
                url("/api/v1/encounters/" + encounterId), org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(authHeaders("ROLE_DOCTOR")), EncounterResponse.class);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody().diagnoses()).hasSize(1);
    }

    @Test
    void createEncounterRejectsRequestsWithoutAuthorizedRole() {
        EncounterRequest createRequest = new EncounterRequest(UUID.randomUUID(), UUID.randomUUID(), null, "Annual physical");
        HttpEntity<EncounterRequest> createEntity = new HttpEntity<>(createRequest, authHeaders("ROLE_PATIENT"));

        ResponseEntity<String> response = restTemplate.postForEntity(url("/api/v1/encounters"), createEntity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void createEncounterRejectsMissingToken() {
        EncounterRequest createRequest = new EncounterRequest(UUID.randomUUID(), UUID.randomUUID(), null, "Annual physical");
        HttpEntity<EncounterRequest> createEntity = new HttpEntity<>(createRequest);

        ResponseEntity<String> response = restTemplate.postForEntity(url("/api/v1/encounters"), createEntity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void getUnknownEncounterReturnsNotFound() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/v1/encounters/" + UUID.randomUUID()), org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(authHeaders("ROLE_DOCTOR")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
