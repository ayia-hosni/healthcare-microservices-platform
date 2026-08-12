package com.healthplatform.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * One RestClient per downstream REST API this gateway aggregates. Each is a client of the
 * exact same endpoints the frontend would otherwise call directly — no new inter-service
 * protocol, just a single entry point for the frontend instead of nine.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient patientServiceClient(DownstreamServicesProperties properties) {
        return RestClient.create(properties.patient().baseUrl());
    }

    @Bean
    public RestClient doctorServiceClient(DownstreamServicesProperties properties) {
        return RestClient.create(properties.doctor().baseUrl());
    }

    @Bean
    public RestClient appointmentServiceClient(DownstreamServicesProperties properties) {
        return RestClient.create(properties.appointment().baseUrl());
    }

    @Bean
    public RestClient billingServiceClient(DownstreamServicesProperties properties) {
        return RestClient.create(properties.billing().baseUrl());
    }
}
