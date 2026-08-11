package com.healthplatform.emr.web;

import com.healthplatform.emr.service.ClinicalDocumentService;
import com.healthplatform.emr.web.dto.fhir.BundleDto;
import com.healthplatform.emr.web.dto.fhir.DocumentReferenceDto;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The FHIR-facing read surface for clinical documents. Deliberately reuses the same bearer-JWT
 * authentication and role model every other endpoint on this platform already uses (see
 * common.security.JwtVerifier / ADR-0004) — a FHIR client is just another authenticated caller,
 * not a special integration with its own auth path.
 */
@RestController
@RequestMapping("/fhir/DocumentReference")
@Tag(name = "FHIR / DocumentReference")
public class FhirDocumentReferenceController {

    private final ClinicalDocumentService clinicalDocumentService;

    public FhirDocumentReferenceController(ClinicalDocumentService clinicalDocumentService) {
        this.clinicalDocumentService = clinicalDocumentService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('DOCTOR','NURSE','ADMIN','PATIENT')")
    public ResponseEntity<BundleDto> search(@RequestParam UUID patient) {
        return ResponseEntity.ok(clinicalDocumentService.findByPatient(patient));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('DOCTOR','NURSE','ADMIN','PATIENT')")
    public ResponseEntity<DocumentReferenceDto> read(@PathVariable UUID id) {
        return ResponseEntity.ok(clinicalDocumentService.findById(id));
    }
}
