package com.healthplatform.patient.service;

import com.healthplatform.common.events.ConsentGrantedEvent;
import com.healthplatform.common.events.ConsentRevokedEvent;
import com.healthplatform.common.events.PatientRegisteredEvent;
import com.healthplatform.common.events.PatientUpdatedEvent;
import com.healthplatform.common.exception.ResourceNotFoundException;
import com.healthplatform.patient.domain.Patient;
import com.healthplatform.patient.event.PatientEventPublisher;
import com.healthplatform.patient.repository.PatientRepository;
import com.healthplatform.patient.web.dto.PatientRequest;
import com.healthplatform.patient.web.dto.PatientResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PatientServiceTest {

    private PatientRepository patientRepository;
    private PatientEventPublisher eventPublisher;
    private PatientService patientService;

    @BeforeEach
    void setUp() {
        patientRepository = mock(PatientRepository.class);
        eventPublisher = mock(PatientEventPublisher.class);
        patientService = new PatientService(patientRepository, eventPublisher);

        when(patientRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void registerSavesPatientAndPublishesEvent() {
        UUID id = UUID.randomUUID();
        PatientRequest request = new PatientRequest(id, "Aya", "Hosni",
                LocalDate.of(1990, 5, 1), "aya@example.com", "555-1234");

        PatientResponse response = patientService.register(request);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.firstName()).isEqualTo("Aya");
        assertThat(response.lastName()).isEqualTo("Hosni");
        assertThat(response.email()).isEqualTo("aya@example.com");
        assertThat(response.phoneNumber()).isEqualTo("555-1234");

        verify(patientRepository).save(any(Patient.class));

        var eventCaptor = org.mockito.ArgumentCaptor.forClass(PatientRegisteredEvent.class);
        verify(eventPublisher).publishPatientRegistered(eq(id.toString()), eventCaptor.capture(), any());
        assertThat(eventCaptor.getValue().firstName()).isEqualTo("Aya");
        assertThat(eventCaptor.getValue().patientId()).isEqualTo(id.toString());
    }

    @Test
    void getByIdReturnsMappedResponseWhenFound() {
        UUID id = UUID.randomUUID();
        Patient patient = new Patient(id, "Aya", "Hosni", LocalDate.of(1990, 5, 1), "aya@example.com");
        when(patientRepository.findById(id)).thenReturn(Optional.of(patient));

        PatientResponse response = patientService.getById(id);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.lastName()).isEqualTo("Hosni");
    }

    @Test
    void getByIdThrowsWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(patientRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> patientService.getById(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    @Test
    void searchReturnsMappedResults() {
        // search() loads all patients via findAll() and filters by name in Java (names are
        // encrypted, so no SQL query); p3 should be filtered out.
        Patient p1 = new Patient(UUID.randomUUID(), "Aya", "Hosni", LocalDate.of(1990, 5, 1), "aya@example.com");
        Patient p2 = new Patient(UUID.randomUUID(), "Amina", "Hosni", LocalDate.of(1985, 3, 3), "amina@example.com");
        Patient p3 = new Patient(UUID.randomUUID(), "Sam", "Nolan", LocalDate.of(1978, 1, 1), "sam@example.com");
        when(patientRepository.findAll()).thenReturn(List.of(p1, p2, p3));

        List<PatientResponse> results = patientService.search("Hosni");

        assertThat(results).hasSize(2);
        assertThat(results).extracting(PatientResponse::firstName).containsExactly("Aya", "Amina");
    }

    @Test
    void updatePhoneNumberUpdatesWhenFound() {
        UUID id = UUID.randomUUID();
        Patient patient = new Patient(id, "Aya", "Hosni", LocalDate.of(1990, 5, 1), "aya@example.com");
        when(patientRepository.findById(id)).thenReturn(Optional.of(patient));

        PatientResponse response = patientService.updatePhoneNumber(id, "555-9999");

        assertThat(response.phoneNumber()).isEqualTo("555-9999");
        assertThat(patient.getPhoneNumber()).isEqualTo("555-9999");

        var eventCaptor = org.mockito.ArgumentCaptor.forClass(PatientUpdatedEvent.class);
        verify(eventPublisher).publishPatientUpdated(eq(id.toString()), eventCaptor.capture(), any());
        assertThat(eventCaptor.getValue().phoneNumber()).isEqualTo("555-9999");
    }

    @Test
    void updatePhoneNumberThrowsWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(patientRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> patientService.updatePhoneNumber(id, "555-9999"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    @Test
    void grantConsentMarksPatientAndPublishesEvent() {
        UUID id = UUID.randomUUID();
        Patient patient = new Patient(id, "Aya", "Hosni", LocalDate.of(1990, 5, 1), "aya@example.com");
        when(patientRepository.findById(id)).thenReturn(Optional.of(patient));

        PatientResponse response = patientService.grantConsent(id);

        assertThat(response.consentGranted()).isTrue();
        assertThat(response.consentUpdatedAt()).isNotNull();
        assertThat(patient.isConsentGranted()).isTrue();

        var eventCaptor = org.mockito.ArgumentCaptor.forClass(ConsentGrantedEvent.class);
        verify(eventPublisher).publishConsentGranted(eq(id.toString()), eventCaptor.capture(), any());
        assertThat(eventCaptor.getValue().patientId()).isEqualTo(id.toString());
    }

    @Test
    void grantConsentThrowsWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(patientRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> patientService.grantConsent(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    @Test
    void revokeConsentClearsFlagAndPublishesEvent() {
        UUID id = UUID.randomUUID();
        Patient patient = new Patient(id, "Aya", "Hosni", LocalDate.of(1990, 5, 1), "aya@example.com");
        patient.grantConsent(java.time.Instant.now());
        when(patientRepository.findById(id)).thenReturn(Optional.of(patient));

        PatientResponse response = patientService.revokeConsent(id);

        assertThat(response.consentGranted()).isFalse();
        assertThat(patient.isConsentGranted()).isFalse();

        var eventCaptor = org.mockito.ArgumentCaptor.forClass(ConsentRevokedEvent.class);
        verify(eventPublisher).publishConsentRevoked(eq(id.toString()), eventCaptor.capture(), any());
        assertThat(eventCaptor.getValue().patientId()).isEqualTo(id.toString());
    }

    @Test
    void revokeConsentThrowsWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(patientRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> patientService.revokeConsent(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(id.toString());
    }
}
