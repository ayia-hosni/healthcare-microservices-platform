package com.healthplatform.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Allowed browser origins for cross-origin requests, one list shared by every service so a
 * new frontend origin is a single env var change (CORS_ALLOWED_ORIGINS), not a hunt through
 * N SecurityConfig classes. Defaults to the platform's own frontend origin rather than "*" —
 * see identity-service's original CORS comment: HIPAA-adjacent platforms should never rely on
 * permissive CORS defaults.
 */
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {
    private List<String> allowedOrigins = List.of("https://app.example-health.com");

    public List<String> getAllowedOrigins() { return allowedOrigins; }
    public void setAllowedOrigins(List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins; }
}
