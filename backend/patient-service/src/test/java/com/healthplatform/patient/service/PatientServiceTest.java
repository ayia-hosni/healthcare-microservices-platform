package com.healthplatform.patient.service;

import com.healthplatform.common.events.PatientRegisteredEvent;
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
        Patient p1 = new Patient(UUID.randomUUID(), "Aya", "Hosni", LocalDate.of(1990, 5, 1), "aya@example.com");
        Patient p2 = new Patient(UUID.randomUUID(), "Amina", "Hosni", LocalDate.of(1985, 3, 3), "amina@example.com");
        when(patientRepository.searchByName("Hosni")).thenReturn(List.of(p1, p2));

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
    }

    @Test
    void updatePhoneNumberThrowsWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(patientRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> patientService.updatePhoneNumber(id, "555-9999"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(id.toString());
    }
}
