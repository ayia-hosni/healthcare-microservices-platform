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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DoctorServiceTest {

    private DoctorRepository doctorRepository;
    private DepartmentRepository departmentRepository;
    private DoctorEventPublisher eventPublisher;
    private DoctorService doctorService;

    @BeforeEach
    void setUp() {
        doctorRepository = mock(DoctorRepository.class);
        departmentRepository = mock(DepartmentRepository.class);
        eventPublisher = mock(DoctorEventPublisher.class);
        doctorService = new DoctorService(doctorRepository, departmentRepository, eventPublisher);

        when(doctorRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createReusesExistingDepartmentWhenNameMatches() {
        UUID id = UUID.randomUUID();
        Department cardiology = new Department("Cardiology");
        DoctorRequest request = new DoctorRequest(id, "Amina", "Nasser", "Cardiology", "Cardiology");
        when(departmentRepository.findByNameIgnoreCase("Cardiology")).thenReturn(Optional.of(cardiology));

        DoctorResponse response = doctorService.create(request);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.departmentName()).isEqualTo("Cardiology");
        verify(departmentRepository, never()).save(any());
    }

    @Test
    void createCreatesNewDepartmentWhenNoneMatches() {
        UUID id = UUID.randomUUID();
        DoctorRequest request = new DoctorRequest(id, "Amina", "Nasser", "Neurology", "Neurology");
        when(departmentRepository.findByNameIgnoreCase("Neurology")).thenReturn(Optional.empty());
        when(departmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DoctorResponse response = doctorService.create(request);

        assertThat(response.departmentName()).isEqualTo("Neurology");
        verify(departmentRepository).save(any(Department.class));
    }

    @Test
    void createPublishesDoctorCreatedEvent() {
        UUID id = UUID.randomUUID();
        Department cardiology = new Department("Cardiology");
        DoctorRequest request = new DoctorRequest(id, "Amina", "Nasser", "Cardiology", "Cardiology");
        when(departmentRepository.findByNameIgnoreCase("Cardiology")).thenReturn(Optional.of(cardiology));

        doctorService.create(request);

        var captor = org.mockito.ArgumentCaptor.forClass(DoctorCreatedEvent.class);
        verify(eventPublisher).publishDoctorCreated(eq(id.toString()), captor.capture(), any());
        assertThat(captor.getValue().doctorId()).isEqualTo(id.toString());
        assertThat(captor.getValue().specialty()).isEqualTo("Cardiology");
    }

    @Test
    void getByIdReturnsMappedResponseWhenFound() {
        UUID id = UUID.randomUUID();
        Department cardiology = new Department("Cardiology");
        Doctor doctor = new Doctor(id, "Amina", "Nasser", "Cardiology", cardiology);
        when(doctorRepository.findById(id)).thenReturn(Optional.of(doctor));

        DoctorResponse response = doctorService.getById(id);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.departmentName()).isEqualTo("Cardiology");
    }

    @Test
    void getByIdThrowsWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(doctorRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> doctorService.getById(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    @Test
    void findBySpecialtyReturnsMappedResults() {
        Department cardiology = new Department("Cardiology");
        Doctor d1 = new Doctor(UUID.randomUUID(), "Amina", "Nasser", "Cardiology", cardiology);
        Doctor d2 = new Doctor(UUID.randomUUID(), "Omar", "Farid", "Cardiology", cardiology);
        when(doctorRepository.findBySpecialtyIgnoreCase("Cardiology")).thenReturn(List.of(d1, d2));

        List<DoctorResponse> results = doctorService.findBySpecialty("Cardiology");

        assertThat(results).hasSize(2);
        assertThat(results).extracting(DoctorResponse::firstName).containsExactly("Amina", "Omar");
    }
}
