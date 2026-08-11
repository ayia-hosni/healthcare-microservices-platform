package com.healthplatform.audit.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthplatform.audit.domain.AuditRecord;
import com.healthplatform.audit.repository.AuditRecordRepository;
import com.healthplatform.common.events.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Subscribes to every domain-event topic in the platform. Each handler method is identical
 * on purpose: audit-service's job is to record WHAT happened, not to interpret it, so there's
 * no branching logic per event type beyond capturing which topic it came from.
 */
@Component
public class DomainEventAuditConsumer {

    private static final Logger log = LoggerFactory.getLogger(DomainEventAuditConsumer.class);

    private final AuditRecordRepository auditRecordRepository;
    private final ObjectMapper objectMapper;

    public DomainEventAuditConsumer(AuditRecordRepository auditRecordRepository, ObjectMapper objectMapper) {
        this.auditRecordRepository = auditRecordRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = Topics.PATIENT_EVENTS, groupId = "audit-service")
    @Transactional
    public void onPatientEvent(String rawEvent) { record(Topics.PATIENT_EVENTS, rawEvent); }

    @KafkaListener(topics = Topics.DOCTOR_EVENTS, groupId = "audit-service")
    @Transactional
    public void onDoctorEvent(String rawEvent) { record(Topics.DOCTOR_EVENTS, rawEvent); }

    @KafkaListener(topics = Topics.APPOINTMENT_EVENTS, groupId = "audit-service")
    @Transactional
    public void onAppointmentEvent(String rawEvent) { record(Topics.APPOINTMENT_EVENTS, rawEvent); }

    @KafkaListener(topics = Topics.EMR_EVENTS, groupId = "audit-service")
    @Transactional
    public void onEmrEvent(String rawEvent) { record(Topics.EMR_EVENTS, rawEvent); }

    @KafkaListener(topics = Topics.BILLING_EVENTS, groupId = "audit-service")
    @Transactional
    public void onBillingEvent(String rawEvent) { record(Topics.BILLING_EVENTS, rawEvent); }

    private void record(String topic, String rawEvent) {
        try {
            JsonNode node = objectMapper.readTree(rawEvent);
            AuditRecord auditRecord = new AuditRecord(
                    node.path("eventType").asText("UNKNOWN"),
                    node.path("aggregateId").asText(""),
                    topic,
                    node.path("correlationId").asText(null),
                    node.path("payload").toString()
            );
            auditRecordRepository.save(auditRecord);
        } catch (Exception e) {
            log.error("Failed to record audit event from topic {}: {}", topic, e.getMessage(), e);
            // Rethrow so the container's error handler (see KafkaDlqConfig) can retry and,
            // on repeated failure, dead-letter the record instead of silently dropping it —
            // an audit log with silent gaps defeats the point of an audit log.
            throw new RuntimeException("Failed to record audit event from topic " + topic, e);
        }
    }
}
