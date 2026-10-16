package com.healthplatform.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code app.jwt.*}. Downstream services only verify tokens, so they need the
 * issuer's JWKS location and the expected {@code iss} value — never a signing key.
 */
@ConfigurationProperties(prefix = "app.jwt")
public class JwtSecurityProperties {

    /** identity-service's JWKS endpoint, e.g. {@code http://identity-service:8081/oauth2/jwks}. */
    private String jwksUri;

    /** Expected {@code iss} claim; must equal identity-service's own {@code app.jwt.issuer-uri}. */
    private String issuerUri;

    public String getJwksUri() { return jwksUri; }
    public void setJwksUri(String jwksUri) { this.jwksUri = jwksUri; }
    public String getIssuerUri() { return issuerUri; }
    public void setIssuerUri(String issuerUri) { this.issuerUri = issuerUri; }
}
