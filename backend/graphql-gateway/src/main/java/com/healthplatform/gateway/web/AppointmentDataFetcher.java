package com.healthplatform.gateway.web;

import com.healthplatform.gateway.config.AuthHeaderInterceptor;
import com.healthplatform.gateway.dto.AppointmentDto;
import com.healthplatform.gateway.dto.BookAppointmentInput;
import com.healthplatform.gateway.dto.DoctorDto;
import com.healthplatform.gateway.dto.PatientDto;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Controller
public class AppointmentDataFetcher {

    private final RestClient appointmentServiceClient;
    private final RestClient patientServiceClient;
    private final RestClient doctorServiceClient;

    public AppointmentDataFetcher(RestClient appointmentServiceClient, RestClient patientServiceClient,
                                   RestClient doctorServiceClient) {
        this.appointmentServiceClient = appointmentServiceClient;
        this.patientServiceClient = patientServiceClient;
        this.doctorServiceClient = doctorServiceClient;
    }

    @QueryMapping
    public AppointmentDto appointment(@Argument String id,
                                       @ContextValue(name = AuthHeaderInterceptor.AUTHORIZATION_CONTEXT_KEY, required = false) String authorization) {
        try {
            return appointmentServiceClient.get()
                    .uri("/api/v1/appointments/{id}", id)
                    .headers(headers -> DownstreamAuth.addTo(headers, authorization))
                    .retrieve()
                    .body(AppointmentDto.class);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    @MutationMapping
    public AppointmentDto bookAppointment(@Argument BookAppointmentInput input,
                                           @ContextValue(name = AuthHeaderInterceptor.AUTHORIZATION_CONTEXT_KEY, required = false) String authorization) {
        return appointmentServiceClient.post()
                .uri("/api/v1/appointments")
                .headers(headers -> DownstreamAuth.addTo(headers, authorization))
                .body(input)
                .retrieve()
                .body(AppointmentDto.class);
    }

    /**
     * Resolved on demand, per-query, by calling out to patient-service — the actual
     * aggregation value of the gateway: a client asking for `appointment { patient { ... } }`
     * gets one round trip instead of two. Not batched (no DataLoader) — fine at this schema's
     * current shape (single appointment per query, no list-of-appointments field yet); would
     * be the first thing to add if a list query is introduced later.
     */
    @SchemaMapping(typeName = "Appointment", field = "patient")
    public PatientDto patient(AppointmentDto appointment,
                               @ContextValue(name = AuthHeaderInterceptor.AUTHORIZATION_CONTEXT_KEY, required = false) String authorization) {
        return PatientDataFetcher.fetchPatient(patientServiceClient, appointment.patientId().toString(), authorization);
    }

    @SchemaMapping(typeName = "Appointment", field = "doctor")
    public DoctorDto doctor(AppointmentDto appointment,
                             @ContextValue(name = AuthHeaderInterceptor.AUTHORIZATION_CONTEXT_KEY, required = false) String authorization) {
        return DoctorDataFetcher.fetchDoctor(doctorServiceClient, appointment.doctorId().toString(), authorization);
    }
}
