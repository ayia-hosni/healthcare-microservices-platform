package com.healthplatform.emr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "services")
public record DownstreamServicesProperties(Service patient) {
    public record Service(String baseUrl) {}
}
