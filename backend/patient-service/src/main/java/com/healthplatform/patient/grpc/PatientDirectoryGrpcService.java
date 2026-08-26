package com.healthplatform.patient.grpc;

import com.healthplatform.common.grpc.PatientDirectoryGrpc;
import com.healthplatform.common.grpc.PatientDirectoryRequest;
import com.healthplatform.common.grpc.PatientDirectoryResponse;
import com.healthplatform.patient.domain.Patient;
import com.healthplatform.patient.repository.PatientRepository;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

import java.util.UUID;

/**
 * Wider read surface than PatientLookupGrpcService, added per ADR-0004 for callers that need
 * the full patient record rather than an existence check — today that's graphql-gateway's
 * `patient` field (see PatientLookupGrpcClient in graphql-gateway). Same PatientRepository
 * read PatientService already uses for the REST API; unlike DoctorDirectory, no lazy
 * association here so no @Transactional is needed.
 */
@GrpcService
public class PatientDirectoryGrpcService extends PatientDirectoryGrpc.PatientDirectoryImplBase {

    private final PatientRepository patientRepository;

    public PatientDirectoryGrpcService(PatientRepository patientRepository) {
        this.patientRepository = patientRepository;
    }

    @Override
    public void getPatient(PatientDirectoryRequest request, StreamObserver<PatientDirectoryResponse> responseObserver) {
        UUID patientId;
        try {
            patientId = UUID.fromString(request.getPatientId());
        } catch (IllegalArgumentException e) {
            responseObserver.onNext(PatientDirectoryResponse.newBuilder().setExists(false).build());
            responseObserver.onCompleted();
            return;
        }

        PatientDirectoryResponse response = patientRepository.findById(patientId)
                .map(this::toResponse)
                .orElseGet(() -> PatientDirectoryResponse.newBuilder().setExists(false).build());

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    private PatientDirectoryResponse toResponse(Patient patient) {
        return PatientDirectoryResponse.newBuilder()
                .setExists(true)
                .setId(patient.getId().toString())
                .setFirstName(patient.getFirstName())
                .setLastName(patient.getLastName())
                .setDateOfBirth(patient.getDateOfBirth().toString())
                .setEmail(patient.getEmail())
                .setPhoneNumber(patient.getPhoneNumber() != null ? patient.getPhoneNumber() : "")
                .build();
    }
}
