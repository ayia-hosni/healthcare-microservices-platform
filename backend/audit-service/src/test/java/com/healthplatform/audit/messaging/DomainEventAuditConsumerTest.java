package com.healthplatform.audit.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthplatform.audit.domain.AuditRecord;
import com.healthplatform.audit.repository.AuditRecordRepository;
import com.healthplatform.common.events.Topics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the generic domain-event recorder. Repository is mocked; a real ObjectMapper
 * is used since JSON parsing behaviour (valid vs malformed) is exactly what's under test.
 *
 * The class's own Javadoc is explicit that failures must NOT be silently swallowed, so a
 * malformed payload must propagate (letting the Kafka container's DefaultErrorHandler / DLQ
 * config retry and eventually dead-letter it) rather than being caught and logged only.
 */
class DomainEventAuditConsumerTest {

    private AuditRecordRepository auditRecordRepository;
    private DomainEventAuditConsumer consumer;

    @BeforeEach
    void setUp() {
        auditRecordRepository = mock(AuditRecordRepository.class);
        when(auditRecordRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        consumer = new DomainEventAuditConsumer(auditRecordRepository, new ObjectMapper());
    }

    @Test
    void recordsValidPatientEventWithSourceTopicAndFieldsExtracted() {
        String rawEvent = """
                {
                  "eventId": "8f14e45f-ceea-4d8b-b7c2-6b1e9a6b1a1a",
                  "aggregateId": "patient-123",
                  "eventType": "PATIENT_REGISTERED",
                  "occurredAt": "2026-08-11T10:15:30Z",
                  "version": 1,
                  "correlationId": "corr-456",
                  "payload": { "patientId": "patient-123", "email": "a@example.com" }
                }
                """;

        consumer.onPatientEvent(rawEvent);

        var captor = org.mockito.ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditRecordRepository).save(captor.capture());
        AuditRecord saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo("PATIENT_REGISTERED");
        assertThat(saved.getAggregateId()).isEqualTo("patient-123");
        assertThat(saved.getSourceTopic()).isEqualTo(Topics.PATIENT_EVENTS);
        assertThat(saved.getCorrelationId()).isEqualTo("corr-456");
        assertThat(saved.getPayloadJson()).contains("patientId").contains("patient-123");
    }

    @Test
    void recordsFromEachTopicWithCorrectSourceTopicTag() {
        String rawEvent = "{ \"eventType\": \"X\", \"aggregateId\": \"a1\" }";

        consumer.onDoctorEvent(rawEvent);
        consumer.onAppointmentEvent(rawEvent);
        consumer.onEmrEvent(rawEvent);
        consumer.onBillingEvent(rawEvent);

        var captor = org.mockito.ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditRecordRepository, org.mockito.Mockito.times(4)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(AuditRecord::getSourceTopic)
                .containsExactly(Topics.DOCTOR_EVENTS, Topics.APPOINTMENT_EVENTS, Topics.EMR_EVENTS, Topics.BILLING_EVENTS);
    }

    @Test
    void missingOptionalFieldsFallBackToDefaults() {
        // No eventType/aggregateId/correlationId at all.
        String rawEvent = "{ \"payload\": { \"x\": 1 } }";

        consumer.onPatientEvent(rawEvent);

        var captor = org.mockito.ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditRecordRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("UNKNOWN");
        assertThat(captor.getValue().getAggregateId()).isEqualTo("");
        assertThat(captor.getValue().getCorrelationId()).isNull();
    }

    @Test
    void malformedJsonPropagatesInsteadOfBeingSilentlySwallowed() {
        String rawEvent = "{ this is not json ";

        assertThatThrownBy(() -> consumer.onPatientEvent(rawEvent))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to record audit event");

        verify(auditRecordRepository, never()).save(any());
    }
}
