package com.healthplatform.emr.repository;

import com.healthplatform.emr.domain.Encounter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EncounterRepository extends JpaRepository<Encounter, UUID> {
    List<Encounter> findAllByPatientIdOrderByEncounterDateDesc(UUID patientId);
}
