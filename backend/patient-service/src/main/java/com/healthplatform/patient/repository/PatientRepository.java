package com.healthplatform.patient.repository;

import com.healthplatform.patient.domain.Patient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * No name-search query here: firstName/lastName are encrypted at rest (see Patient), so a
 * SQL LIKE can't match them. PatientService.search() decrypts and filters in Java instead.
 */
public interface PatientRepository extends JpaRepository<Patient, UUID> {
}
