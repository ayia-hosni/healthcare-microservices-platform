package com.healthplatform.patient.repository;

import com.healthplatform.patient.domain.Patient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface PatientRepository extends JpaRepository<Patient, UUID> {

    @Query("select p from Patient p where lower(p.lastName) like lower(concat('%', :query, '%')) " +
           "or lower(p.firstName) like lower(concat('%', :query, '%'))")
    List<Patient> searchByName(String query);
}
