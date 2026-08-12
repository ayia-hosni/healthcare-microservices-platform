package com.healthplatform.gateway.web;

import com.healthplatform.gateway.config.AuthHeaderInterceptor;
import com.healthplatform.gateway.dto.DoctorDto;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Controller
public class DoctorDataFetcher {

    private final RestClient doctorServiceClient;

    public DoctorDataFetcher(RestClient doctorServiceClient) {
        this.doctorServiceClient = doctorServiceClient;
    }

    @QueryMapping
    public DoctorDto doctor(@Argument String id,
                             @ContextValue(name = AuthHeaderInterceptor.AUTHORIZATION_CONTEXT_KEY, required = false) String authorization) {
        return fetchDoctor(doctorServiceClient, id, authorization);
    }

    static DoctorDto fetchDoctor(RestClient client, String id, String authorization) {
        try {
            return client.get()
                    .uri("/api/v1/doctors/{id}", id)
                    .headers(headers -> DownstreamAuth.addTo(headers, authorization))
                    .retrieve()
                    .body(DoctorDto.class);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }
}
