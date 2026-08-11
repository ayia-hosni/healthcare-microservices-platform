package com.healthplatform.billing.web;

import com.healthplatform.billing.domain.Invoice;
import com.healthplatform.billing.domain.InvoiceStatus;
import com.healthplatform.billing.repository.InvoiceRepository;
import com.healthplatform.billing.web.dto.InvoiceResponse;
import com.healthplatform.billing.web.dto.PaymentRequest;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of the invoice payment flow against a real Postgres container
 * (Flyway-migrated) and the real JWT security chain. Invoice creation in this service only
 * happens via the AppointmentCreatedEvent Kafka consumer (no REST create endpoint exists), so
 * the invoice is seeded directly through InvoiceRepository — the state-changing part of the
 * flow under test is the real HTTP round trip through POST /api/v1/invoices/{id}/payments.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InvoicePaymentIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("billing_db")
            .withUsername("billing_user")
            .withPassword("billing_pass");

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

    @Autowired
    private InvoiceRepository invoiceRepository;

    private static final String JWT_SECRET = "change-me-in-production-this-must-be-at-least-32-bytes-long";

    private String jwtFor(String username, String... roles) {
        var key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes());
        return Jwts.builder().subject(username).claim("roles", List.of(roles)).signWith(key).compact();
    }

    private HttpHeaders authHeaders(String... roles) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtFor("clerk.aya@example.com", roles));
        return headers;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void payInvoiceInFullEndToEndMarksItPaid() {
        Invoice invoice = new Invoice(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("150.00"),
                Instant.now().plus(30, ChronoUnit.DAYS));
        invoice = invoiceRepository.save(invoice);
        UUID invoiceId = invoice.getId();

        PaymentRequest paymentRequest = new PaymentRequest(new BigDecimal("150.00"), "CREDIT_CARD");
        HttpEntity<PaymentRequest> entity = new HttpEntity<>(paymentRequest, authHeaders("ROLE_BILLING_CLERK"));

        ResponseEntity<InvoiceResponse> payResponse = restTemplate.postForEntity(
                url("/api/v1/invoices/" + invoiceId + "/payments"), entity, InvoiceResponse.class);

        assertThat(payResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(payResponse.getBody()).isNotNull();
        assertThat(payResponse.getBody().status()).isEqualTo(InvoiceStatus.PAID);

        ResponseEntity<InvoiceResponse> getResponse = restTemplate.exchange(
                url("/api/v1/invoices/" + invoiceId), HttpMethod.GET,
                new HttpEntity<>(authHeaders("ROLE_BILLING_CLERK")), InvoiceResponse.class);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody().status()).isEqualTo(InvoiceStatus.PAID);

        Invoice persisted = invoiceRepository.findById(invoiceId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(persisted.getPayments()).hasSize(1);
    }

    @Test
    void payInvoiceRejectsAmountExceedingBalanceEndToEnd() {
        Invoice invoice = new Invoice(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("150.00"),
                Instant.now().plus(30, ChronoUnit.DAYS));
        invoice = invoiceRepository.save(invoice);
        UUID invoiceId = invoice.getId();

        PaymentRequest paymentRequest = new PaymentRequest(new BigDecimal("999.00"), "CREDIT_CARD");
        HttpEntity<PaymentRequest> entity = new HttpEntity<>(paymentRequest, authHeaders("ROLE_BILLING_CLERK"));

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/api/v1/invoices/" + invoiceId + "/payments"), entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        Invoice persisted = invoiceRepository.findById(invoiceId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(InvoiceStatus.PENDING);
        assertThat(persisted.getPayments()).isEmpty();
    }

    @Test
    void payInvoiceRejectsRequestsWithoutAuthorizedRole() {
        Invoice invoice = new Invoice(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("150.00"),
                Instant.now().plus(30, ChronoUnit.DAYS));
        invoice = invoiceRepository.save(invoice);

        PaymentRequest paymentRequest = new PaymentRequest(new BigDecimal("50.00"), "CASH");
        HttpEntity<PaymentRequest> entity = new HttpEntity<>(paymentRequest, authHeaders("ROLE_DOCTOR"));

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/api/v1/invoices/" + invoice.getId() + "/payments"), entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void getUnknownInvoiceReturnsNotFound() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/v1/invoices/" + UUID.randomUUID()), HttpMethod.GET,
                new HttpEntity<>(authHeaders("ROLE_ADMIN")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
