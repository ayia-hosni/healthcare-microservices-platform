package com.healthplatform.billing.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthplatform.billing.domain.Invoice;
import com.healthplatform.billing.repository.InvoiceRepository;
import com.healthplatform.common.events.InvoiceGeneratedEvent;
import com.healthplatform.common.events.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Saga participant: reacts to AppointmentCreatedEvent by generating a pending invoice for
 * the visit. This is the CHOREOGRAPHED half described in appointment-service's booking flow.
 *
 * Idempotency: we check findAllByAppointmentId before creating, so redelivery of the same
 * Kafka message (at-least-once delivery is the default) doesn't produce duplicate invoices.
 */
@Component
public class AppointmentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AppointmentEventConsumer.class);
    private static final BigDecimal STANDARD_VISIT_FEE = new BigDecimal("150.00");

    private final InvoiceRepository invoiceRepository;
    private final BillingEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public AppointmentEventConsumer(InvoiceRepository invoiceRepository, BillingEventPublisher eventPublisher,
                                     ObjectMapper objectMapper) {
        this.invoiceRepository = invoiceRepository;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = Topics.APPOINTMENT_EVENTS, groupId = "billing-service")
    @Transactional
    public void onAppointmentEvent(String rawEvent) {
        try {
            JsonNode node = objectMapper.readTree(rawEvent);
            if (!"AppointmentCreatedEvent".equals(node.path("eventType").asText())) {
                return; // ignore cancellations/other event types on this same topic
            }
            JsonNode payload = node.path("payload");
            UUID appointmentId = UUID.fromString(payload.path("appointmentId").asText());
            UUID patientId = UUID.fromString(payload.path("patientId").asText());

            if (!invoiceRepository.findAllByAppointmentId(appointmentId).isEmpty()) {
                return; // already processed this appointment (idempotent consumer)
            }

            Invoice invoice = new Invoice(patientId, appointmentId, STANDARD_VISIT_FEE,
                    Instant.now().plus(30, ChronoUnit.DAYS));
            invoiceRepository.save(invoice);

            eventPublisher.publishInvoiceGenerated(
                    invoice.getId().toString(),
                    new InvoiceGeneratedEvent(invoice.getId().toString(), patientId.toString(),
                            appointmentId.toString(), invoice.getAmount(), invoice.getCurrency()),
                    node.path("correlationId").asText(null)
            );
        } catch (Exception e) {
            log.error("Failed to process appointment event for billing: {}", e.getMessage(), e);
            // Rethrow so the container's error handler (see KafkaDlqConfig) can retry and,
            // on repeated failure, dead-letter the record instead of silently dropping it.
            // Swallowing here would also let the surrounding @Transactional commit whatever
            // partial state exists, since Spring only rolls back on a propagated exception.
            throw new RuntimeException("Failed to process appointment event for billing", e);
        }
    }
}
