package com.healthplatform.emr.service;

import com.healthplatform.common.exception.ResourceNotFoundException;
import com.healthplatform.emr.domain.Encounter;
import com.healthplatform.emr.event.EmrEventPublisher;
import com.healthplatform.emr.repository.EncounterRepository;
import com.healthplatform.emr.web.dto.DiagnosisRequest;
import com.healthplatform.emr.web.dto.EncounterRequest;
import com.healthplatform.emr.web.dto.EncounterResponse;
import com.healthplatform.emr.web.dto.MedicationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EncounterServiceTest {

    private EncounterRepository encounterRepository;
    private EmrEventPublisher eventPublisher;
    private EncounterService encounterService;

    @BeforeEach
    void setUp() {
        encounterRepository = mock(EncounterRepository.class);
        eventPublisher = mock(EmrEventPublisher.class);
        when(encounterRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        encounterService = new EncounterService(encounterRepository, eventPublisher);
    }

    @Test
    void createEncounterPersistsAndReturnsPatientAndDoctor() {
        UUID patientId = UUID.randomUUID();
        UUID doctorId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID();

        EncounterResponse response = encounterService.createEncounter(
                new EncounterRequest(patientId, doctorId, appointmentId, "Routine checkup"));

        verify(encounterRepository).save(any(Encounter.class));
        assertThat(response.patientId()).isEqualTo(patientId);
        assertThat(response.doctorId()).isEqualTo(doctorId);
        assertThat(response.notes()).isEqualTo("Routine checkup");
        assertThat(response.diagnoses()).isEmpty();
        assertThat(response.medications()).isEmpty();
    }

    @Test
    void addDiagnosisAppendsToExistingEncounter() {
        UUID encounterId = UUID.randomUUID();
        Encounter encounter = new Encounter(UUID.randomUUID(), UUID.randomUUID(), null, null);
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));

        EncounterResponse response = encounterService.addDiagnosis(encounterId,
                new DiagnosisRequest("J45.909", "Unspecified asthma"));

        assertThat(response.diagnoses()).containsExactly("J45.909 - Unspecified asthma");
    }

    @Test
    void addDiagnosisThrowsWhenEncounterMissing() {
        UUID encounterId = UUID.randomUUID();
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> encounterService.addDiagnosis(encounterId,
                new DiagnosisRequest("J45.909", "Unspecified asthma")))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(encounterId.toString());
    }

    @Test
    void addMedicationSavesEncounterAndPublishesPrescriptionEvent() {
        UUID encounterId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID doctorId = UUID.randomUUID();
        Encounter encounter = new Encounter(patientId, doctorId, null, null);
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));

        EncounterResponse response = encounterService.addMedication(encounterId,
                new MedicationRequest("Albuterol", "90mcg inhaler"));

        assertThat(response.medications()).containsExactly("Albuterol 90mcg inhaler");
        verify(encounterRepository).save(encounter);
        verify(eventPublisher).publishPrescriptionCreated(any(), argThat(event ->
                event.medication().equals("Albuterol")
                        && event.dosage().equals("90mcg inhaler")
                        && event.patientId().equals(patientId.toString())
                        && event.doctorId().equals(doctorId.toString())
        ), any());
    }

    @Test
    void addMedicationThrowsWhenEncounterMissing() {
        UUID encounterId = UUID.randomUUID();
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> encounterService.addMedication(encounterId,
                new MedicationRequest("Albuterol", "90mcg inhaler")))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void getByIdReturnsEncounterWhenPresent() {
        UUID encounterId = UUID.randomUUID();
        Encounter encounter = new Encounter(UUID.randomUUID(), UUID.randomUUID(), null, "Follow-up");
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));

        EncounterResponse response = encounterService.getById(encounterId);

        assertThat(response.notes()).isEqualTo("Follow-up");
    }

    @Test
    void getByIdThrowsWhenMissing() {
        UUID encounterId = UUID.randomUUID();
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> encounterService.getById(encounterId))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
