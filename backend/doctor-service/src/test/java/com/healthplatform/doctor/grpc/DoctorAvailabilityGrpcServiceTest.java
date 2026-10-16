package com.healthplatform.doctor.grpc;

import com.healthplatform.common.grpc.CheckAvailabilityRequest;
import com.healthplatform.common.grpc.CheckAvailabilityResponse;
import com.healthplatform.doctor.domain.AvailabilitySlot;
import com.healthplatform.doctor.domain.Department;
import com.healthplatform.doctor.domain.Doctor;
import com.healthplatform.doctor.repository.DoctorRepository;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the actual time-window matching logic (unlike DoctorLookup/DoctorDirectory, which are
 * plain existence checks) — this gates every appointment booking/reschedule, so a bug here
 * either lets bookings through outside working hours or wrongly blocks valid ones.
 */
class DoctorAvailabilityGrpcServiceTest {

    private DoctorRepository doctorRepository;
    private DoctorAvailabilityGrpcService service;
    private StreamObserver<CheckAvailabilityResponse> responseObserver;

    private final UUID doctorId = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        doctorRepository = mock(DoctorRepository.class);
        service = new DoctorAvailabilityGrpcService(doctorRepository);
        responseObserver = mock(StreamObserver.class);
    }

    private Doctor doctorWithSlot(DayOfWeek dayOfWeek, LocalTime start, LocalTime end) {
        Doctor doctor = new Doctor(doctorId, "Amina", "Nasser", "Cardiology", new Department("Cardiology"));
        doctor.addAvailabilitySlot(new AvailabilitySlot(dayOfWeek, start, end));
        return doctor;
    }

    @Test
    void returnsAvailableWhenRequestedWindowFitsInsideASlot() {
        when(doctorRepository.findById(doctorId))
                .thenReturn(Optional.of(doctorWithSlot(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0))));
        var request = CheckAvailabilityRequest.newBuilder()
                .setDoctorId(doctorId.toString()).setDayOfWeek("MONDAY")
                .setStartTime("10:00").setEndTime("10:30").build();

        service.checkAvailability(request, responseObserver);

        var captor = org.mockito.ArgumentCaptor.forClass(CheckAvailabilityResponse.class);
        verify(responseObserver).onNext(captor.capture());
        assertThat(captor.getValue().getAvailable()).isTrue();
        verify(responseObserver).onCompleted();
    }

    @Test
    void returnsUnavailableWhenRequestedWindowExtendsPastSlotEnd() {
        when(doctorRepository.findById(doctorId))
                .thenReturn(Optional.of(doctorWithSlot(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0))));
        var request = CheckAvailabilityRequest.newBuilder()
                .setDoctorId(doctorId.toString()).setDayOfWeek("MONDAY")
                .setStartTime("11:45").setEndTime("12:15").build();

        service.checkAvailability(request, responseObserver);

        var captor = org.mockito.ArgumentCaptor.forClass(CheckAvailabilityResponse.class);
        verify(responseObserver).onNext(captor.capture());
        assertThat(captor.getValue().getAvailable()).isFalse();
    }

    @Test
    void returnsUnavailableForADifferentDayOfWeek() {
        when(doctorRepository.findById(doctorId))
                .thenReturn(Optional.of(doctorWithSlot(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0))));
        var request = CheckAvailabilityRequest.newBuilder()
                .setDoctorId(doctorId.toString()).setDayOfWeek("TUESDAY")
                .setStartTime("10:00").setEndTime("10:30").build();

        service.checkAvailability(request, responseObserver);

        var captor = org.mockito.ArgumentCaptor.forClass(CheckAvailabilityResponse.class);
        verify(responseObserver).onNext(captor.capture());
        assertThat(captor.getValue().getAvailable()).isFalse();
    }

    @Test
    void returnsUnavailableWhenDoctorHasNoSlotsAtAll() {
        Doctor doctor = new Doctor(doctorId, "Amina", "Nasser", "Cardiology", new Department("Cardiology"));
        when(doctorRepository.findById(doctorId)).thenReturn(Optional.of(doctor));
        var request = CheckAvailabilityRequest.newBuilder()
                .setDoctorId(doctorId.toString()).setDayOfWeek("MONDAY")
                .setStartTime("10:00").setEndTime("10:30").build();

        service.checkAvailability(request, responseObserver);

        var captor = org.mockito.ArgumentCaptor.forClass(CheckAvailabilityResponse.class);
        verify(responseObserver).onNext(captor.capture());
        assertThat(captor.getValue().getAvailable()).isFalse();
    }

    @Test
    void returnsUnavailableWhenDoctorDoesNotExist() {
        when(doctorRepository.findById(doctorId)).thenReturn(Optional.empty());
        var request = CheckAvailabilityRequest.newBuilder()
                .setDoctorId(doctorId.toString()).setDayOfWeek("MONDAY")
                .setStartTime("10:00").setEndTime("10:30").build();

        service.checkAvailability(request, responseObserver);

        var captor = org.mockito.ArgumentCaptor.forClass(CheckAvailabilityResponse.class);
        verify(responseObserver).onNext(captor.capture());
        assertThat(captor.getValue().getAvailable()).isFalse();
    }

    @Test
    void returnsUnavailableOnMalformedRequestInsteadOfThrowing() {
        var request = CheckAvailabilityRequest.newBuilder()
                .setDoctorId("not-a-uuid").setDayOfWeek("MONDAY")
                .setStartTime("10:00").setEndTime("10:30").build();

        service.checkAvailability(request, responseObserver);

        var captor = org.mockito.ArgumentCaptor.forClass(CheckAvailabilityResponse.class);
        verify(responseObserver).onNext(captor.capture());
        assertThat(captor.getValue().getAvailable()).isFalse();
        verify(responseObserver, org.mockito.Mockito.never()).onError(any());
    }
}
