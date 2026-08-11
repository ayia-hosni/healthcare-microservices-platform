package com.healthplatform.emr.repository;

import com.healthplatform.emr.domain.ClinicalDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ClinicalDocumentRepository extends JpaRepository<ClinicalDocument, UUID> {
    List<ClinicalDocument> findAllByPatientIdOrderByCreatedAtDesc(UUID patientId);
}
