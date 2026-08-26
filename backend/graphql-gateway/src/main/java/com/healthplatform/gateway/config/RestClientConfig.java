package com.healthplatform.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * One RestClient per downstream REST API this gateway still aggregates. Patient/doctor lookups
 * moved to gRPC (see the grpc package) — appointment/billing don't expose a gRPC server, so
 * they stay REST clients of the exact same endpoints the frontend would otherwise call
 * directly.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient appointmentServiceClient(DownstreamServicesProperties properties) {
        return RestClient.create(properties.appointment().baseUrl());
    }

    @Bean
    public RestClient billingServiceClient(DownstreamServicesProperties properties) {
        return RestClient.create(properties.billing().baseUrl());
    }
}
