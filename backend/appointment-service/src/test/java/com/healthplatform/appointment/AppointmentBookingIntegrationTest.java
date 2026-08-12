package com.healthplatform.appointment;

import com.healthplatform.appointment.grpc.BookingValidationClient;
import com.healthplatform.appointment.web.dto.AppointmentResponse;
import com.healthplatform.appointment.web.dto.BookAppointmentRequest;
import com.healthplatform.common.dto.ErrorResponse;
import com.healthplatform.common.outbox.OutboxEventRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end regression test for the one thing that actually matters in this service: two real
 * HTTP booking requests for the SAME doctor/slot must not both succeed. This runs against a real
 * Postgres (Testcontainers), so it exercises the pessimistic lock in
 * AppointmentRepository.findConflicting() AND the DB unique constraint backstop (uk_doctor_slot)
 * together — the actual path a concurrency regression would need to break through, unlike the
 * mocked unit tests in AppointmentServiceTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AppointmentBookingIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("appointment_db")
            .withUsername("appointment_user")
            .withPassword("appointment_pass");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    // This test's focus is the double-booking/idempotency path against a real Postgres, not
    // cross-service connectivity — patient-service/doctor-service aren't part of this test's
    // Testcontainers setup, so the real gRPC stubs would otherwise fail every booking with
    // UNAVAILABLE. A plain mock() no-ops the void requireXExists methods, i.e. "exists".
    @MockBean
    private BookingValidationClient bookingValidationClient;

    private static final String JWT_SECRET = "change-me-in-production-this-must-be-at-least-32-bytes-long";

    private String jwtFor(String username, String... roles) {
        var key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes());
        return Jwts.builder().subject(username).claim("roles", List.of(roles)).signWith(key).compact();
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(jwtFor("aya@example.com", "ROLE_PATIENT"));
        return headers;
    }

    private String url() {
        return "http://localhost:" + port + "/api/v1/appointments";
    }

    @Test
    void booksAppointmentThenRejectsARealDoubleBookingForTheSameDoctorAndSlot() {
        UUID doctorId = UUID.randomUUID();
        Instant slotStart = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        Instant slotEnd = slotStart.plus(30, ChronoUnit.MINUTES);

        BookAppointmentRequest firstBooking = new BookAppointmentRequest(
                UUID.randomUUID(), doctorId, slotStart, slotEnd, "integration-key-1-" + UUID.randomUUID());

        ResponseEntity<AppointmentResponse> firstResponse = restTemplate.exchange(
                url(), HttpMethod.POST, new HttpEntity<>(firstBooking, authHeaders()), AppointmentResponse.class);

        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(firstResponse.getBody()).isNotNull();
        assertThat(firstResponse.getBody().id()).isNotNull();
        assertThat(firstResponse.getBody().doctorId()).isEqualTo(doctorId);

        // Second request: different patient, different idempotency key, SAME doctor + SAME start
        // time — a genuine double-booking attempt, not a retry.
        BookAppointmentRequest secondBooking = new BookAppointmentRequest(
                UUID.randomUUID(), doctorId, slotStart, slotEnd, "integration-key-2-" + UUID.randomUUID());

        ResponseEntity<ErrorResponse> secondResponse = restTemplate.exchange(
                url(), HttpMethod.POST, new HttpEntity<>(secondBooking, authHeaders()), ErrorResponse.class);

        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(secondResponse.getBody()).isNotNull();
        assertThat(secondResponse.getBody().error()).isEqualTo("SLOT_TAKEN");

        // The successful booking must have gone through the outbox in the same transaction
        // (see AppointmentEventPublisher / ADR-0002) — confirm a row landed, without chasing Kafka.
        assertThat(outboxEventRepository.count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void replayingTheSameIdempotencyKeyOverHttpReturnsTheOriginalBookingNotADuplicate() {
        UUID doctorId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        Instant slotStart = Instant.now().plus(4, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        Instant slotEnd = slotStart.plus(30, ChronoUnit.MINUTES);
        String idempotencyKey = "integration-replay-key-" + UUID.randomUUID();

        BookAppointmentRequest request = new BookAppointmentRequest(patientId, doctorId, slotStart, slotEnd, idempotencyKey);

        ResponseEntity<AppointmentResponse> first = restTemplate.exchange(
                url(), HttpMethod.POST, new HttpEntity<>(request, authHeaders()), AppointmentResponse.class);
        ResponseEntity<AppointmentResponse> replay = restTemplate.exchange(
                url(), HttpMethod.POST, new HttpEntity<>(request, authHeaders()), AppointmentResponse.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody()).isNotNull();
        assertThat(first.getBody()).isNotNull();
        assertThat(replay.getBody().id()).isEqualTo(first.getBody().id());
    }
}
