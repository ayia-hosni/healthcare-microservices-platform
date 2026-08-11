package com.healthplatform.emr.service;

import com.healthplatform.common.events.PrescriptionCreatedEvent;
import com.healthplatform.common.exception.ResourceNotFoundException;
import com.healthplatform.emr.domain.Diagnosis;
import com.healthplatform.emr.domain.Encounter;
import com.healthplatform.emr.domain.Medication;
import com.healthplatform.emr.event.EmrEventPublisher;
import com.healthplatform.emr.repository.EncounterRepository;
import com.healthplatform.emr.web.dto.DiagnosisRequest;
import com.healthplatform.emr.web.dto.EncounterRequest;
import com.healthplatform.emr.web.dto.EncounterResponse;
import com.healthplatform.emr.web.dto.MedicationRequest;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class EncounterService {

    private final EncounterRepository encounterRepository;
    private final EmrEventPublisher eventPublisher;

    public EncounterService(EncounterRepository encounterRepository, EmrEventPublisher eventPublisher) {
        this.encounterRepository = encounterRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public EncounterResponse createEncounter(EncounterRequest request) {
        Encounter encounter = new Encounter(request.patientId(), request.doctorId(),
                request.appointmentId(), request.notes());
        encounterRepository.save(encounter);
        return toResponse(encounter);
    }

    @Transactional
    public EncounterResponse addDiagnosis(UUID encounterId, DiagnosisRequest request) {
        Encounter encounter = getOrThrow(encounterId);
        encounter.addDiagnosis(new Diagnosis(request.icd10Code(), request.description()));
        return toResponse(encounter);
    }

    @Transactional
    public EncounterResponse addMedication(UUID encounterId, MedicationRequest request) {
        Encounter encounter = getOrThrow(encounterId);
        Medication medication = new Medication(request.name(), request.dosage());
        encounter.addMedication(medication);
        encounterRepository.save(encounter);

        eventPublisher.publishPrescriptionCreated(
                medication.getId() != null ? medication.getId().toString() : UUID.randomUUID().toString(),
                new PrescriptionCreatedEvent(UUID.randomUUID().toString(), encounter.getPatientId().toString(),
                        encounter.getDoctorId().toString(), medication.getName(), medication.getDosage()),
                MDC.get("correlationId")
        );

        return toResponse(encounter);
    }

    @Transactional(readOnly = true)
    public EncounterResponse getById(UUID id) {
        return toResponse(getOrThrow(id));
    }

    private Encounter getOrThrow(UUID id) {
        return encounterRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Encounter not found: " + id));
    }

    private EncounterResponse toResponse(Encounter e) {
        return new EncounterResponse(e.getId(), e.getPatientId(), e.getDoctorId(), e.getEncounterDate(), e.getNotes(),
                e.getDiagnoses().stream().map(d -> d.getIcd10Code() + " - " + d.getDescription()).toList(),
                e.getMedications().stream().map(m -> m.getName() + " " + m.getDosage()).toList());
    }
}
