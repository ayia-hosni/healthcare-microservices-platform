package com.healthplatform.gateway.grpc;

import com.healthplatform.common.grpc.DoctorDirectoryGrpc;
import com.healthplatform.common.grpc.DoctorDirectoryRequest;
import com.healthplatform.common.grpc.DoctorDirectoryResponse;
import com.healthplatform.gateway.dto.DoctorDto;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Replaces the REST round trip to doctor-service with a direct gRPC call to its
 * DoctorDirectory service (see DoctorDirectoryGrpcService) — the wider read surface ADR-0004
 * added alongside the existing, narrower DoctorLookup existence check appointment-service
 * uses for booking validation. Used for both the top-level `doctor` query and the nested
 * Appointment.doctor field.
 */
@Component
public class DoctorLookupGrpcClient {

    private static final Duration DEADLINE = Duration.ofSeconds(2);

    @GrpcClient("doctor-service")
    private DoctorDirectoryGrpc.DoctorDirectoryBlockingStub doctorDirectoryStub;

    public DoctorDto fetch(String doctorId) {
        DoctorDirectoryResponse response = doctorDirectoryStub
                .withDeadlineAfter(DEADLINE.toMillis(), TimeUnit.MILLISECONDS)
                .getDoctor(DoctorDirectoryRequest.newBuilder().setDoctorId(doctorId).build());
        if (!response.getExists()) {
            return null;
        }
        return new DoctorDto(
                UUID.fromString(response.getId()),
                response.getFirstName(),
                response.getLastName(),
                response.getSpecialty(),
                response.getDepartmentName());
    }
}
