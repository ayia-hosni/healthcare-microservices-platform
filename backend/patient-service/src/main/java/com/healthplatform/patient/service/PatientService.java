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
import org.slf4j.MDC;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
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

    /**
     * Names are encrypted at rest (see Patient), so this loads every patient, decrypts, and
     * matches in Java. O(n) per call: fine at demo scale, not a real search index.
     */
    @Transactional(readOnly = true)
    public List<PatientResponse> search(String query) {
        String needle = query.toLowerCase(Locale.ROOT);
        return patientRepository.findAll().stream()
                .filter(p -> p.getFirstName().toLowerCase(Locale.ROOT).contains(needle)
                        || p.getLastName().toLowerCase(Locale.ROOT).contains(needle))
                .map(this::toResponse)
                .toList();
    }

    @CacheEvict(value = "patients", key = "#id")
    @Transactional
    public PatientResponse updatePhoneNumber(UUID id, String phoneNumber) {
        Patient patient = patientRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found: " + id));
        patient.setPhoneNumber(phoneNumber);

        eventPublisher.publishPatientUpdated(
                patient.getId().toString(),
                new PatientUpdatedEvent(patient.getId().toString(), patient.getPhoneNumber()),
                MDC.get("correlationId")
        );

        return toResponse(patient);
    }

    @CacheEvict(value = "patients", key = "#id")
    @Transactional
    public PatientResponse grantConsent(UUID id) {
        Patient patient = patientRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found: " + id));
        Instant now = Instant.now();
        patient.grantConsent(now);

        eventPublisher.publishConsentGranted(
                patient.getId().toString(),
                new ConsentGrantedEvent(patient.getId().toString(), now),
                MDC.get("correlationId")
        );

        return toResponse(patient);
    }

    @CacheEvict(value = "patients", key = "#id")
    @Transactional
    public PatientResponse revokeConsent(UUID id) {
        Patient patient = patientRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found: " + id));
        Instant now = Instant.now();
        patient.revokeConsent(now);

        eventPublisher.publishConsentRevoked(
                patient.getId().toString(),
                new ConsentRevokedEvent(patient.getId().toString(), now),
                MDC.get("correlationId")
        );

        return toResponse(patient);
    }

    private PatientResponse toResponse(Patient p) {
        return new PatientResponse(p.getId(), p.getFirstName(), p.getLastName(), p.getDateOfBirth(),
                p.getEmail(), p.getPhoneNumber(), p.isConsentGranted(), p.getConsentUpdatedAt());
    }
}
