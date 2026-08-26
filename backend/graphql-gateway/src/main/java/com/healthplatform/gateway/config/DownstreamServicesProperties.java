package com.healthplatform.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "services")
public record DownstreamServicesProperties(
        Service appointment,
        Service billing
) {
    public record Service(String baseUrl) {}
}
