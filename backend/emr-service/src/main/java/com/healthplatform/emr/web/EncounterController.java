package com.healthplatform.emr.web;

import com.healthplatform.emr.service.ClinicalDocumentService;
import com.healthplatform.emr.service.EncounterService;
import com.healthplatform.emr.web.dto.*;
import com.healthplatform.emr.web.dto.fhir.DocumentReferenceDto;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/encounters")
@Tag(name = "EMR / Encounters")
public class EncounterController {

    private final EncounterService encounterService;
    private final ClinicalDocumentService clinicalDocumentService;

    public EncounterController(EncounterService encounterService, ClinicalDocumentService clinicalDocumentService) {
        this.encounterService = encounterService;
        this.clinicalDocumentService = clinicalDocumentService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('DOCTOR','NURSE','ADMIN')")
    public ResponseEntity<EncounterResponse> create(@Valid @RequestBody EncounterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(encounterService.createEncounter(request));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('DOCTOR','NURSE','ADMIN','PATIENT')")
    public ResponseEntity<EncounterResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(encounterService.getById(id));
    }

    @PostMapping("/{id}/diagnoses")
    @PreAuthorize("hasRole('DOCTOR')")
    public ResponseEntity<EncounterResponse> addDiagnosis(@PathVariable UUID id, @Valid @RequestBody DiagnosisRequest request) {
        return ResponseEntity.ok(encounterService.addDiagnosis(id, request));
    }

    @PostMapping("/{id}/medications")
    @PreAuthorize("hasRole('DOCTOR')")
    public ResponseEntity<EncounterResponse> addMedication(@PathVariable UUID id, @Valid @RequestBody MedicationRequest request) {
        return ResponseEntity.ok(encounterService.addMedication(id, request));
    }

    /**
     * Stores a clinical document (discharge summary, referral letter, lab report, ...) for this
     * encounter and returns it as a FHIR DocumentReference — the same shape served back by
     * GET /fhir/DocumentReference. Defaults to a discharge summary (LOINC 18842-5) since that's
     * the common case; callers can override the coding for other document types.
     */
    @PostMapping(value = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('DOCTOR','NURSE','ADMIN')")
    public ResponseEntity<DocumentReferenceDto> uploadDocument(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file,
            @RequestParam String title,
            @RequestParam(defaultValue = "http://loinc.org") String typeSystem,
            @RequestParam(defaultValue = "18842-5") String typeCode,
            @RequestParam(defaultValue = "Discharge summary") String typeDisplay) {
        DocumentReferenceDto created = clinicalDocumentService.uploadDocument(id, file, title, typeSystem, typeCode, typeDisplay);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
