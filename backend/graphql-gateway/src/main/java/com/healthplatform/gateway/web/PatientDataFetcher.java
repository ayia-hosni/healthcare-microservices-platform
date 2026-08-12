package com.healthplatform.gateway.web;

import com.healthplatform.gateway.config.AuthHeaderInterceptor;
import com.healthplatform.gateway.dto.PatientDto;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Controller
public class PatientDataFetcher {

    private final RestClient patientServiceClient;

    public PatientDataFetcher(RestClient patientServiceClient) {
        this.patientServiceClient = patientServiceClient;
    }

    @QueryMapping
    public PatientDto patient(@Argument String id,
                               @ContextValue(name = AuthHeaderInterceptor.AUTHORIZATION_CONTEXT_KEY, required = false) String authorization) {
        return fetchPatient(patientServiceClient, id, authorization);
    }

    static PatientDto fetchPatient(RestClient client, String id, String authorization) {
        try {
            return client.get()
                    .uri("/api/v1/patients/{id}", id)
                    .headers(headers -> DownstreamAuth.addTo(headers, authorization))
                    .retrieve()
                    .body(PatientDto.class);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }
}
