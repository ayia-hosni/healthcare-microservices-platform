package com.healthplatform.doctor.grpc;

import com.healthplatform.common.grpc.DoctorLookupGrpc;
import com.healthplatform.common.grpc.DoctorLookupRequest;
import com.healthplatform.common.grpc.DoctorLookupResponse;
import com.healthplatform.doctor.domain.Doctor;
import com.healthplatform.doctor.repository.DoctorRepository;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

import java.util.UUID;

/**
 * Server side of the synchronous doctor-existence check appointment-service calls before
 * booking. Reuses DoctorRepository directly — same data access path DoctorService already
 * uses for the REST API, just a narrower response shape (no need to expose the full Doctor
 * record, including availability, over gRPC for an exists-check).
 */
@GrpcService
public class DoctorLookupGrpcService extends DoctorLookupGrpc.DoctorLookupImplBase {

    private final DoctorRepository doctorRepository;

    public DoctorLookupGrpcService(DoctorRepository doctorRepository) {
        this.doctorRepository = doctorRepository;
    }

    @Override
    public void getDoctor(DoctorLookupRequest request, StreamObserver<DoctorLookupResponse> responseObserver) {
        UUID doctorId;
        try {
            doctorId = UUID.fromString(request.getDoctorId());
        } catch (IllegalArgumentException e) {
            responseObserver.onNext(DoctorLookupResponse.newBuilder().setExists(false).build());
            responseObserver.onCompleted();
            return;
        }

        DoctorLookupResponse response = doctorRepository.findById(doctorId)
                .map(this::toResponse)
                .orElseGet(() -> DoctorLookupResponse.newBuilder().setExists(false).build());

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    private DoctorLookupResponse toResponse(Doctor doctor) {
        return DoctorLookupResponse.newBuilder()
                .setExists(true)
                .setFirstName(doctor.getFirstName())
                .setLastName(doctor.getLastName())
                .setSpecialty(doctor.getSpecialty())
                .build();
    }
}
