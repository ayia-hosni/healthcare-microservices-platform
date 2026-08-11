package com.healthplatform.emr.repository;

import com.healthplatform.emr.domain.Allergy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AllergyRepository extends JpaRepository<Allergy, UUID> {
    List<Allergy> findAllByPatientId(UUID patientId);
}
