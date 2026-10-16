package com.healthplatform.doctor.grpc;

import com.healthplatform.common.grpc.CheckAvailabilityRequest;
import com.healthplatform.common.grpc.CheckAvailabilityResponse;
import com.healthplatform.common.grpc.DoctorAvailabilityGrpc;
import com.healthplatform.doctor.repository.DoctorRepository;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Server side of the availability check appointment-service runs before booking/rescheduling
 * (see BookingValidationClient.requireDoctorAvailable). @Transactional is needed here (unlike
 * DoctorLookupGrpcService's plain existence check) because doctor.getAvailability() is a lazy
 * association and this service runs with open-in-view disabled.
 */
@GrpcService
public class DoctorAvailabilityGrpcService extends DoctorAvailabilityGrpc.DoctorAvailabilityImplBase {

    private final DoctorRepository doctorRepository;

    public DoctorAvailabilityGrpcService(DoctorRepository doctorRepository) {
        this.doctorRepository = doctorRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public void checkAvailability(CheckAvailabilityRequest request, StreamObserver<CheckAvailabilityResponse> responseObserver) {
        boolean available;
        try {
            UUID doctorId = UUID.fromString(request.getDoctorId());
            DayOfWeek dayOfWeek = DayOfWeek.valueOf(request.getDayOfWeek());
            LocalTime startTime = LocalTime.parse(request.getStartTime());
            LocalTime endTime = LocalTime.parse(request.getEndTime());

            available = doctorRepository.findById(doctorId)
                    .map(doctor -> doctor.getAvailability().stream().anyMatch(slot ->
                            slot.getDayOfWeek() == dayOfWeek
                                    && !slot.getStartTime().isAfter(startTime)
                                    && !slot.getEndTime().isBefore(endTime)))
                    .orElse(false);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            // Malformed doctorId/dayOfWeek/time: treat as "not available" rather than a 500 —
            // mirrors DoctorLookupGrpcService's handling of a bad UUID.
            available = false;
        }

        responseObserver.onNext(CheckAvailabilityResponse.newBuilder().setAvailable(available).build());
        responseObserver.onCompleted();
    }
}
