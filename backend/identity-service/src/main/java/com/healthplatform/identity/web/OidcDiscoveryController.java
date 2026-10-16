package com.healthplatform.identity.web;

import com.healthplatform.identity.config.JwtProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Minimal OpenID Provider metadata (RFC 8414 / OIDC Discovery). This service is not a full
 * OAuth2 Authorization Server — there is no {@code /authorize} redirect flow — but publishing
 * {@code issuer} + {@code jwks_uri} lets any standard resource server or client be pointed at
 * it by issuer URL alone.
 */
@RestController
@Tag(name = "OAuth2", description = "Token verification metadata")
public class OidcDiscoveryController {

    private final JwtProperties properties;

    public OidcDiscoveryController(JwtProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/.well-known/openid-configuration")
    @Operation(summary = "OpenID provider metadata")
    public Map<String, Object> metadata() {
        String issuer = properties.getIssuerUri();
        return Map.of(
                "issuer", issuer,
                "jwks_uri", issuer + "/oauth2/jwks",
                "token_endpoint", issuer + "/api/v1/auth/login",
                "grant_types_supported", List.of("password", "refresh_token"),
                "response_types_supported", List.of("token"),
                "subject_types_supported", List.of("public"),
                "id_token_signing_alg_values_supported", List.of("RS256"),
                "scopes_supported", List.of("openid")
        );
    }
}
