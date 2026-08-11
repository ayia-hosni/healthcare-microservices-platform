package com.healthplatform.patient.service;

import com.healthplatform.common.events.PatientRegisteredEvent;
import com.healthplatform.common.exception.ResourceNotFoundException;
import com.healthplatform.patient.domain.Patient;
import com.healthplatform.patient.event.PatientEventPublisher;
import com.healthplatform.patient.repository.PatientRepository;
import com.healthplatform.patient.web.dto.PatientRequest;
import com.healthplatform.patient.web.dto.PatientResponse;
import org.slf4j.MDC;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class PatientService {

    private final PatientRepository patientRepository;
    private final PatientEventPublisher eventPublisher;

    public PatientService(PatientRepository patientRepository, PatientEventPublisher eventPublisher) {
        this.patientRepository = patientRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public PatientResponse register(PatientRequest request) {
        Patient patient = new Patient(request.id(), request.firstName(), request.lastName(),
                request.dateOfBirth(), request.email());
        patient.setPhoneNumber(request.phoneNumber());
        patientRepository.save(patient);

        eventPublisher.publishPatientRegistered(
                patient.getId().toString(),
                new PatientRegisteredEvent(patient.getId().toString(), patient.getFirstName(),
                        patient.getLastName(), patient.getDateOfBirth(), patient.getEmail()),
                MDC.get("correlationId")
        );

        return toResponse(patient);
    }

    @Cacheable(value = "patients", key = "#id") // frequently-accessed patient lookups served from Redis
    @Transactional(readOnly = true)
    public PatientResponse getById(UUID id) {
        Patient patient = patientRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found: " + id));
        return toResponse(patient);
    }

    @Transactional(readOnly = true)
    public List<PatientResponse> search(String query) {
        return patientRepository.searchByName(query).stream().map(this::toResponse).toList();
    }

    @CacheEvict(value = "patients", key = "#id")
    @Transactional
    public PatientResponse updatePhoneNumber(UUID id, String phoneNumber) {
        Patient patient = patientRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found: " + id));
        patient.setPhoneNumber(phoneNumber);
        return toResponse(patient);
    }

    private PatientResponse toResponse(Patient p) {
        return new PatientResponse(p.getId(), p.getFirstName(), p.getLastName(), p.getDateOfBirth(),
                p.getEmail(), p.getPhoneNumber());
    }
}
