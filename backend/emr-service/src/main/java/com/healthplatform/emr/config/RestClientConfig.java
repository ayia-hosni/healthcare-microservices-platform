package com.healthplatform.emr.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(DownstreamServicesProperties.class)
public class RestClientConfig {

    @Bean
    public RestClient patientServiceRestClient(DownstreamServicesProperties properties) {
        return RestClient.create(properties.patient().baseUrl());
    }
}
