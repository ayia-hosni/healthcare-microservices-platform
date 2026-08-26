package com.healthplatform.gateway.grpc;

import com.healthplatform.common.grpc.PatientDirectoryGrpc;
import com.healthplatform.common.grpc.PatientDirectoryRequest;
import com.healthplatform.common.grpc.PatientDirectoryResponse;
import com.healthplatform.gateway.dto.PatientDto;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Replaces the REST round trip to patient-service with a direct gRPC call to its
 * PatientDirectory service (see PatientDirectoryGrpcService) — the wider read surface ADR-0004
 * added alongside the existing, narrower PatientLookup existence check appointment-service
 * uses for booking validation. Used for both the top-level `patient` query and the nested
 * Appointment.patient field.
 */
@Component
public class PatientLookupGrpcClient {

    private static final Duration DEADLINE = Duration.ofSeconds(2);

    @GrpcClient("patient-service")
    private PatientDirectoryGrpc.PatientDirectoryBlockingStub patientDirectoryStub;

    public PatientDto fetch(String patientId) {
        PatientDirectoryResponse response = patientDirectoryStub
                .withDeadlineAfter(DEADLINE.toMillis(), TimeUnit.MILLISECONDS)
                .getPatient(PatientDirectoryRequest.newBuilder().setPatientId(patientId).build());
        if (!response.getExists()) {
            return null;
        }
        return new PatientDto(
                UUID.fromString(response.getId()),
                response.getFirstName(),
                response.getLastName(),
                response.getDateOfBirth(),
                response.getEmail(),
                response.getPhoneNumber());
    }
}
