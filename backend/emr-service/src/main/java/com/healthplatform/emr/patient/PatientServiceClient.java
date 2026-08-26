package com.healthplatform.emr.patient;

import com.healthplatform.common.exception.BusinessException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;
import java.util.UUID;

/**
 * The referral workflow needs full patient demographics (name, DOB, contact info) to build a
 * FHIR Patient resource — the platform's existing gRPC PatientLookup service only answers
 * "does this id exist?" (a boolean, see appointment-service's BookingValidationClient), not
 * demographics, so a REST call to patient-service's own GET /api/v1/patients/{id} is the right
 * tool here rather than extending that narrower proto. Forwards the caller's bearer token
 * (same DownstreamAuth pattern graphql-gateway already uses) rather than minting a service
 * credential, since patient-service's read endpoint is role-gated per-caller, not open.
 */
@Component
public class PatientServiceClient {

    private final RestClient patientServiceRestClient;

    public PatientServiceClient(RestClient patientServiceRestClient) {
        this.patientServiceRestClient = patientServiceRestClient;
    }

    public Optional<PatientDto> getById(UUID id, String authorization) {
        try {
            PatientDto dto = patientServiceRestClient.get()
                    .uri("/api/v1/patients/{id}", id)
                    .headers(headers -> {
                        if (authorization != null) {
                            headers.set(HttpHeaders.AUTHORIZATION, authorization);
                        }
                    })
                    .retrieve()
                    .body(PatientDto.class);
            return Optional.ofNullable(dto);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            throw new BusinessException("PATIENT_SERVICE_UNAVAILABLE",
                    "Could not reach patient-service to fetch demographics: " + e.getMessage());
        }
    }
}
