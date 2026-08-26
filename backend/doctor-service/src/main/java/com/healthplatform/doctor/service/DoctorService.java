package com.healthplatform.doctor.service;

import com.healthplatform.common.events.DoctorCreatedEvent;
import com.healthplatform.common.exception.ResourceNotFoundException;
import com.healthplatform.doctor.domain.Department;
import com.healthplatform.doctor.domain.Doctor;
import com.healthplatform.doctor.event.DoctorEventPublisher;
import com.healthplatform.doctor.repository.DepartmentRepository;
import com.healthplatform.doctor.repository.DoctorRepository;
import com.healthplatform.doctor.web.dto.DoctorRequest;
import com.healthplatform.doctor.web.dto.DoctorResponse;
import org.slf4j.MDC;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class DoctorService {

    private final DoctorRepository doctorRepository;
    private final DepartmentRepository departmentRepository;
    private final DoctorEventPublisher eventPublisher;

    public DoctorService(DoctorRepository doctorRepository, DepartmentRepository departmentRepository,
                          DoctorEventPublisher eventPublisher) {
        this.doctorRepository = doctorRepository;
        this.departmentRepository = departmentRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public DoctorResponse create(DoctorRequest request) {
        Department department = departmentRepository.findByNameIgnoreCase(request.departmentName())
                .orElseGet(() -> departmentRepository.save(new Department(request.departmentName())));

        Doctor doctor = new Doctor(request.id(), request.firstName(), request.lastName(),
                request.specialty(), department);
        doctorRepository.save(doctor);

        eventPublisher.publishDoctorCreated(
                doctor.getId().toString(),
                new DoctorCreatedEvent(doctor.getId().toString(), doctor.getFirstName(), doctor.getLastName(),
                        doctor.getSpecialty(), department.getId().toString()),
                MDC.get("correlationId")
        );

        return toResponse(doctor);
    }

    @Cacheable(value = "doctor-availability", key = "#specialty") // hot path: patients browsing by specialty
    @Transactional(readOnly = true)
    public List<DoctorResponse> findBySpecialty(String specialty) {
        return doctorRepository.findBySpecialtyIgnoreCase(specialty).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<DoctorResponse> findAll() {
        return doctorRepository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public DoctorResponse getById(UUID id) {
        Doctor doctor = doctorRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found: " + id));
        return toResponse(doctor);
    }

    private DoctorResponse toResponse(Doctor d) {
        return new DoctorResponse(d.getId(), d.getFirstName(), d.getLastName(), d.getSpecialty(),
                d.getDepartment() != null ? d.getDepartment().getName() : null);
    }
}
