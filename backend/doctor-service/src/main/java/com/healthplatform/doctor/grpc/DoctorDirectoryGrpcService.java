package com.healthplatform.doctor.grpc;

import com.healthplatform.common.grpc.DoctorDirectoryGrpc;
import com.healthplatform.common.grpc.DoctorDirectoryRequest;
import com.healthplatform.common.grpc.DoctorDirectoryResponse;
import com.healthplatform.doctor.domain.Doctor;
import com.healthplatform.doctor.repository.DoctorRepository;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Wider read surface than DoctorLookupGrpcService, added per ADR-0004 for callers that need
 * the full doctor record rather than an existence check — today that's graphql-gateway's
 * `doctor` field (see DoctorLookupGrpcClient in graphql-gateway). Same DoctorRepository read
 * DoctorService already uses for the REST API. @Transactional is needed here (unlike the
 * plain existence check) because department is a lazy association and this service runs with
 * open-in-view disabled — see DoctorService's own readOnly-transactional getters.
 */
@GrpcService
public class DoctorDirectoryGrpcService extends DoctorDirectoryGrpc.DoctorDirectoryImplBase {

    private final DoctorRepository doctorRepository;

    public DoctorDirectoryGrpcService(DoctorRepository doctorRepository) {
        this.doctorRepository = doctorRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public void getDoctor(DoctorDirectoryRequest request, StreamObserver<DoctorDirectoryResponse> responseObserver) {
        UUID doctorId;
        try {
            doctorId = UUID.fromString(request.getDoctorId());
        } catch (IllegalArgumentException e) {
            responseObserver.onNext(DoctorDirectoryResponse.newBuilder().setExists(false).build());
            responseObserver.onCompleted();
            return;
        }

        DoctorDirectoryResponse response = doctorRepository.findById(doctorId)
                .map(this::toResponse)
                .orElseGet(() -> DoctorDirectoryResponse.newBuilder().setExists(false).build());

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    private DoctorDirectoryResponse toResponse(Doctor doctor) {
        DoctorDirectoryResponse.Builder builder = DoctorDirectoryResponse.newBuilder()
                .setExists(true)
                .setId(doctor.getId().toString())
                .setFirstName(doctor.getFirstName())
                .setLastName(doctor.getLastName())
                .setSpecialty(doctor.getSpecialty());
        if (doctor.getDepartment() != null) {
            builder.setDepartmentName(doctor.getDepartment().getName());
        }
        return builder.build();
    }
}
