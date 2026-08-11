package com.healthplatform.doctor.repository;

import com.healthplatform.doctor.domain.Doctor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DoctorRepository extends JpaRepository<Doctor, UUID> {
    List<Doctor> findBySpecialtyIgnoreCase(String specialty);
}
