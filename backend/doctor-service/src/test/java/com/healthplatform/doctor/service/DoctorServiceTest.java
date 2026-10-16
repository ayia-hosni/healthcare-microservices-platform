package com.healthplatform.doctor.service;

import com.healthplatform.common.events.DoctorCreatedEvent;
import com.healthplatform.common.exception.BusinessException;
import com.healthplatform.common.exception.ResourceNotFoundException;
import com.healthplatform.doctor.domain.AvailabilitySlot;
import com.healthplatform.doctor.domain.Department;
import com.healthplatform.doctor.domain.Doctor;
import com.healthplatform.doctor.event.DoctorEventPublisher;
import com.healthplatform.doctor.repository.DepartmentRepository;
import com.healthplatform.doctor.repository.DoctorRepository;
import com.healthplatform.doctor.web.dto.AvailabilitySlotRequest;
import com.healthplatform.doctor.web.dto.AvailabilitySlotResponse;
import com.healthplatform.doctor.web.dto.DoctorRequest;
import com.healthplatform.doctor.web.dto.DoctorResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
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

    @Test
    void addAvailabilitySlotAddsToDoctorAndReturnsSlot() {
        UUID id = UUID.randomUUID();
        Doctor doctor = new Doctor(id, "Amina", "Nasser", "Cardiology", new Department("Cardiology"));
        when(doctorRepository.findById(id)).thenReturn(Optional.of(doctor));
        AvailabilitySlotRequest request = new AvailabilitySlotRequest(
                DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0));

        AvailabilitySlotResponse response = doctorService.addAvailabilitySlot(id, request);

        assertThat(response.dayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(response.startTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(response.endTime()).isEqualTo(LocalTime.of(12, 0));
        assertThat(doctor.getAvailability()).hasSize(1);
    }

    @Test
    void addAvailabilitySlotRejectsStartTimeNotBeforeEndTime() {
        UUID id = UUID.randomUUID();
        AvailabilitySlotRequest request = new AvailabilitySlotRequest(
                DayOfWeek.MONDAY, LocalTime.of(12, 0), LocalTime.of(9, 0));

        assertThatThrownBy(() -> doctorService.addAvailabilitySlot(id, request))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(doctorRepository);
    }

    @Test
    void addAvailabilitySlotThrowsWhenDoctorNotFound() {
        UUID id = UUID.randomUUID();
        when(doctorRepository.findById(id)).thenReturn(Optional.empty());
        AvailabilitySlotRequest request = new AvailabilitySlotRequest(
                DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0));

        assertThatThrownBy(() -> doctorService.addAvailabilitySlot(id, request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    @Test
    void getAvailabilityReturnsDoctorsSlots() {
        UUID id = UUID.randomUUID();
        Doctor doctor = new Doctor(id, "Amina", "Nasser", "Cardiology", new Department("Cardiology"));
        doctor.addAvailabilitySlot(new AvailabilitySlot(DayOfWeek.TUESDAY, LocalTime.of(13, 0), LocalTime.of(17, 0)));
        when(doctorRepository.findById(id)).thenReturn(Optional.of(doctor));

        List<AvailabilitySlotResponse> results = doctorService.getAvailability(id);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).dayOfWeek()).isEqualTo(DayOfWeek.TUESDAY);
    }

    @Test
    void removeAvailabilitySlotRemovesMatchingSlot() {
        UUID id = UUID.randomUUID();
        Doctor doctor = new Doctor(id, "Amina", "Nasser", "Cardiology", new Department("Cardiology"));
        AvailabilitySlot slot = new AvailabilitySlot(DayOfWeek.WEDNESDAY, LocalTime.of(9, 0), LocalTime.of(11, 0));
        doctor.addAvailabilitySlot(slot);
        when(doctorRepository.findById(id)).thenReturn(Optional.of(doctor));

        doctorService.removeAvailabilitySlot(id, slot.getId());

        assertThat(doctor.getAvailability()).isEmpty();
    }

    @Test
    void removeAvailabilitySlotThrowsWhenSlotNotFound() {
        UUID id = UUID.randomUUID();
        Doctor doctor = new Doctor(id, "Amina", "Nasser", "Cardiology", new Department("Cardiology"));
        when(doctorRepository.findById(id)).thenReturn(Optional.of(doctor));
        UUID missingSlotId = UUID.randomUUID();

        assertThatThrownBy(() -> doctorService.removeAvailabilitySlot(id, missingSlotId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(missingSlotId.toString());
    }
}
