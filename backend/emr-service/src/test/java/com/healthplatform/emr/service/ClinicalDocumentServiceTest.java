package com.healthplatform.emr.service;

import com.healthplatform.common.exception.ResourceNotFoundException;
import com.healthplatform.emr.domain.ClinicalDocument;
import com.healthplatform.emr.domain.Encounter;
import com.healthplatform.emr.event.EmrEventPublisher;
import com.healthplatform.emr.repository.ClinicalDocumentRepository;
import com.healthplatform.emr.repository.EncounterRepository;
import com.healthplatform.emr.storage.DocumentStorageClient;
import com.healthplatform.emr.web.dto.fhir.DocumentReferenceDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class ClinicalDocumentServiceTest {

    private ClinicalDocumentRepository clinicalDocumentRepository;
    private EncounterRepository encounterRepository;
    private DocumentStorageClient storageClient;
    private EmrEventPublisher eventPublisher;
    private ClinicalDocumentService clinicalDocumentService;

    @BeforeEach
    void setUp() {
        clinicalDocumentRepository = mock(ClinicalDocumentRepository.class);
        encounterRepository = mock(EncounterRepository.class);
        storageClient = mock(DocumentStorageClient.class);
        eventPublisher = mock(EmrEventPublisher.class);

        when(clinicalDocumentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        clinicalDocumentService = new ClinicalDocumentService(clinicalDocumentRepository, encounterRepository,
                storageClient, eventPublisher);
    }

    @Test
    void uploadDocumentStoresFileAndPublishesEvents() {
        UUID encounterId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID doctorId = UUID.randomUUID();
        Encounter encounter = new Encounter(patientId, doctorId, null, null);
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(storageClient.store(eq(encounterId), any(), any(), any(), anyLong())).thenReturn("encounters/key.pdf");
        when(storageClient.presignedGetUrl("encounters/key.pdf")).thenReturn("https://minio.local/presigned");

        MockMultipartFile file = new MockMultipartFile("file", "summary.pdf", "application/pdf", "content".getBytes());

        DocumentReferenceDto result = clinicalDocumentService.uploadDocument(encounterId, file, "Discharge summary",
                "http://loinc.org", "18842-5", "Discharge summary");

        assertThat(result.resourceType()).isEqualTo("DocumentReference");
        assertThat(result.status()).isEqualTo("current");
        assertThat(result.subject().reference()).isEqualTo("Patient/" + patientId);
        assertThat(result.content().get(0).attachment().url()).isEqualTo("https://minio.local/presigned");

        verify(clinicalDocumentRepository).save(any(ClinicalDocument.class));
        verify(eventPublisher).publishDocumentReferenceCreated(any(), any(), any());
        verify(eventPublisher).publishNotificationRequested(eq(patientId.toString()), any(), any());
    }

    @Test
    void uploadDocumentThrowsWhenEncounterMissing() {
        UUID encounterId = UUID.randomUUID();
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.empty());
        MockMultipartFile file = new MockMultipartFile("file", "summary.pdf", "application/pdf", "content".getBytes());

        assertThatThrownBy(() -> clinicalDocumentService.uploadDocument(encounterId, file, "Title",
                "http://loinc.org", "18842-5", "Discharge summary"))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(storageClient, clinicalDocumentRepository, eventPublisher);
    }

    @Test
    void uploadDocumentWrapsIoExceptionFromMultipartFile() throws IOException {
        UUID encounterId = UUID.randomUUID();
        Encounter encounter = new Encounter(UUID.randomUUID(), UUID.randomUUID(), null, null);
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));

        org.springframework.web.multipart.MultipartFile file = mock(org.springframework.web.multipart.MultipartFile.class);
        when(file.getInputStream()).thenThrow(new IOException("disk error"));

        assertThatThrownBy(() -> clinicalDocumentService.uploadDocument(encounterId, file, "Title",
                "http://loinc.org", "18842-5", "Discharge summary"))
                .isInstanceOf(DocumentStorageClient.DocumentStorageException.class);

        verifyNoInteractions(clinicalDocumentRepository, eventPublisher);
    }

    @Test
    void findByPatientReturnsBundleOfDocuments() {
        UUID patientId = UUID.randomUUID();
        ClinicalDocument doc = new ClinicalDocument(UUID.randomUUID(), patientId, UUID.randomUUID(),
                "http://loinc.org", "18842-5", "Discharge summary", "Title", "application/pdf", "key.pdf", 42L);
        when(clinicalDocumentRepository.findAllByPatientIdOrderByCreatedAtDesc(patientId)).thenReturn(List.of(doc));
        when(storageClient.presignedGetUrl(any())).thenReturn("https://minio.local/presigned");

        var bundle = clinicalDocumentService.findByPatient(patientId);

        assertThat(bundle.resourceType()).isEqualTo("Bundle");
        assertThat(bundle.total()).isEqualTo(1);
    }

    @Test
    void findByIdThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(clinicalDocumentRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> clinicalDocumentService.findById(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(id.toString());
    }
}
