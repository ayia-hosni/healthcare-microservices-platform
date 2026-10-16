package com.healthplatform.identity.web;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Publishes the public half of the RS256 signing key as a JWK Set. Resource servers fetch
 * this to verify access tokens; nothing here exposes private key material.
 */
@RestController
@Tag(name = "OAuth2", description = "Token verification metadata")
public class JwksController {

    private final RSAKey publicJwk;

    public JwksController(RSAKey signingJwkPublic) {
        this.publicJwk = signingJwkPublic;
    }

    @GetMapping("/oauth2/jwks")
    @Operation(summary = "JSON Web Key Set for verifying access tokens")
    public Map<String, Object> jwks() {
        return new JWKSet(publicJwk).toJSONObject();
    }
}
