package com.healthplatform.patient.grpc;

import com.healthplatform.common.grpc.PatientLookupGrpc;
import com.healthplatform.common.grpc.PatientLookupRequest;
import com.healthplatform.common.grpc.PatientLookupResponse;
import com.healthplatform.patient.domain.Patient;
import com.healthplatform.patient.repository.PatientRepository;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

import java.util.UUID;

/**
 * Server side of the synchronous patient-existence check appointment-service calls before
 * booking. Reuses PatientRepository directly — same data access path PatientService already
 * uses for the REST API, just a narrower response shape (no need to expose the full Patient
 * record over gRPC for an exists-check).
 */
@GrpcService
public class PatientLookupGrpcService extends PatientLookupGrpc.PatientLookupImplBase {

    private final PatientRepository patientRepository;

    public PatientLookupGrpcService(PatientRepository patientRepository) {
        this.patientRepository = patientRepository;
    }

    @Override
    public void getPatient(PatientLookupRequest request, StreamObserver<PatientLookupResponse> responseObserver) {
        UUID patientId;
        try {
            patientId = UUID.fromString(request.getPatientId());
        } catch (IllegalArgumentException e) {
            responseObserver.onNext(PatientLookupResponse.newBuilder().setExists(false).build());
            responseObserver.onCompleted();
            return;
        }

        PatientLookupResponse response = patientRepository.findById(patientId)
                .map(this::toResponse)
                .orElseGet(() -> PatientLookupResponse.newBuilder().setExists(false).build());

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    private PatientLookupResponse toResponse(Patient patient) {
        return PatientLookupResponse.newBuilder()
                .setExists(true)
                .setFirstName(patient.getFirstName())
                .setLastName(patient.getLastName())
                .build();
    }
}
