package com.healthplatform.emr.service;

import com.healthplatform.common.events.DocumentReferenceCreatedEvent;
import com.healthplatform.common.events.NotificationRequestedEvent;
import com.healthplatform.common.exception.ResourceNotFoundException;
import com.healthplatform.emr.domain.ClinicalDocument;
import com.healthplatform.emr.domain.Encounter;
import com.healthplatform.emr.event.EmrEventPublisher;
import com.healthplatform.emr.repository.ClinicalDocumentRepository;
import com.healthplatform.emr.repository.EncounterRepository;
import com.healthplatform.emr.storage.DocumentStorageClient;
import com.healthplatform.emr.web.dto.fhir.AttachmentDto;
import com.healthplatform.emr.web.dto.fhir.BundleDto;
import com.healthplatform.emr.web.dto.fhir.CodeableConceptDto;
import com.healthplatform.emr.web.dto.fhir.CodingDto;
import com.healthplatform.emr.web.dto.fhir.DocumentReferenceDto;
import com.healthplatform.emr.web.dto.fhir.ReferenceDto;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Service
public class ClinicalDocumentService {

    private final ClinicalDocumentRepository clinicalDocumentRepository;
    private final EncounterRepository encounterRepository;
    private final DocumentStorageClient storageClient;
    private final EmrEventPublisher eventPublisher;

    public ClinicalDocumentService(ClinicalDocumentRepository clinicalDocumentRepository,
                                    EncounterRepository encounterRepository,
                                    DocumentStorageClient storageClient,
                                    EmrEventPublisher eventPublisher) {
        this.clinicalDocumentRepository = clinicalDocumentRepository;
        this.encounterRepository = encounterRepository;
        this.storageClient = storageClient;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public DocumentReferenceDto uploadDocument(UUID encounterId, MultipartFile file, String title,
                                                String typeSystem, String typeCode, String typeDisplay) {
        Encounter encounter = encounterRepository.findById(encounterId)
                .orElseThrow(() -> new ResourceNotFoundException("Encounter not found: " + encounterId));

        String objectKey;
        try {
            objectKey = storageClient.store(encounterId, file.getOriginalFilename(), file.getContentType(),
                    file.getInputStream(), file.getSize());
        } catch (IOException e) {
            throw new DocumentStorageClient.DocumentStorageException("Failed to read uploaded file", e);
        }

        ClinicalDocument document = new ClinicalDocument(encounterId, encounter.getPatientId(), encounter.getDoctorId(),
                typeSystem, typeCode, typeDisplay, title, file.getContentType(), objectKey, file.getSize());
        clinicalDocumentRepository.save(document);

        String correlationId = MDC.get("correlationId");

        eventPublisher.publishDocumentReferenceCreated(document.getId().toString(),
                new DocumentReferenceCreatedEvent(document.getId().toString(), encounter.getPatientId().toString(),
                        encounterId.toString(), encounter.getDoctorId().toString(), typeDisplay,
                        file.getContentType(), title),
                correlationId);

        eventPublisher.publishNotificationRequested(encounter.getPatientId().toString(),
                new NotificationRequestedEvent(encounter.getPatientId().toString(), "EMAIL", "DOCUMENT_READY",
                        "{\"documentId\":\"" + document.getId() + "\",\"title\":\"" + title + "\"}"),
                correlationId);

        return toFhirDto(document);
    }

    @Transactional(readOnly = true)
    public BundleDto findByPatient(UUID patientId) {
        return BundleDto.searchset(clinicalDocumentRepository.findAllByPatientIdOrderByCreatedAtDesc(patientId)
                .stream().map(this::toFhirDto).toList());
    }

    @Transactional(readOnly = true)
    public DocumentReferenceDto findById(UUID id) {
        return toFhirDto(getOrThrow(id));
    }

    private ClinicalDocument getOrThrow(UUID id) {
        return clinicalDocumentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("DocumentReference not found: " + id));
    }

    private DocumentReferenceDto toFhirDto(ClinicalDocument doc) {
        return DocumentReferenceDto.of(
                doc.getId().toString(),
                doc.getStatus().fhirCode(),
                new CodeableConceptDto(List.of(new CodingDto(doc.getTypeSystem(), doc.getTypeCode(), doc.getTypeDisplay()))),
                new ReferenceDto("Patient/" + doc.getPatientId()),
                new ReferenceDto("Encounter/" + doc.getEncounterId()),
                new ReferenceDto("Practitioner/" + doc.getAuthorId()),
                doc.getCreatedAt(),
                new AttachmentDto(doc.getContentType(), storageClient.presignedGetUrl(doc.getObjectKey()), doc.getTitle())
        );
    }
}
