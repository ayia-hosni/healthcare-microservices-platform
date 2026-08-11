package com.healthplatform.appointment.grpc;

import com.healthplatform.common.exception.BusinessException;
import com.healthplatform.common.exception.ResourceNotFoundException;
import com.healthplatform.common.grpc.DoctorLookupGrpc;
import com.healthplatform.common.grpc.DoctorLookupRequest;
import com.healthplatform.common.grpc.PatientLookupGrpc;
import com.healthplatform.common.grpc.PatientLookupRequest;
import io.grpc.StatusRuntimeException;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Synchronous existence checks appointment-service didn't have before: booking used to trust
 * a client-supplied patientId/doctorId outright. A short deadline on each call means a slow
 * or down dependency fails the booking fast instead of hanging the request.
 */
@Component
public class BookingValidationClient {

    private static final Duration DEADLINE = Duration.ofSeconds(2);

    @GrpcClient("patient-service")
    private PatientLookupGrpc.PatientLookupBlockingStub patientLookupStub;

    @GrpcClient("doctor-service")
    private DoctorLookupGrpc.DoctorLookupBlockingStub doctorLookupStub;

    public void requirePatientExists(UUID patientId) {
        var response = call(() -> patientLookupStub
                .withDeadlineAfter(DEADLINE.toMillis(), TimeUnit.MILLISECONDS)
                .getPatient(PatientLookupRequest.newBuilder().setPatientId(patientId.toString()).build()));
        if (!response.getExists()) {
            throw new ResourceNotFoundException("Patient not found: " + patientId);
        }
    }

    public void requireDoctorExists(UUID doctorId) {
        var response = call(() -> doctorLookupStub
                .withDeadlineAfter(DEADLINE.toMillis(), TimeUnit.MILLISECONDS)
                .getDoctor(DoctorLookupRequest.newBuilder().setDoctorId(doctorId.toString()).build()));
        if (!response.getExists()) {
            throw new ResourceNotFoundException("Doctor not found: " + doctorId);
        }
    }

    private <T> T call(Supplier<T> grpcCall) {
        try {
            return grpcCall.get();
        } catch (StatusRuntimeException e) {
            // DEADLINE_EXCEEDED/UNAVAILABLE etc.: surfaced as a business error rather than a
            // 500, since from the caller's perspective this is "couldn't book right now," not
            // an unhandled server fault.
            throw new BusinessException(
                    "VALIDATION_UNAVAILABLE", "Could not validate the appointment right now: " + e.getStatus().getCode());
        }
    }
}
