package com.healthplatform.identity.service;

import com.healthplatform.identity.config.JwtProperties;
import com.healthplatform.identity.domain.Role;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Issues short-lived RS256 access tokens.
 *
 * Design decision: access tokens are stateless (never checked against the DB), which is why
 * their TTL is kept short. Anything requiring immediate revocation (logout, password change,
 * account lock) invalidates the *refresh* token instead, so the blast radius of a leaked access
 * token is bounded by its short expiry.
 *
 * Verification is not this service's job beyond its own endpoints — downstream services fetch
 * the public key from {@code /oauth2/jwks} and verify independently.
 */
@Service
public class JwtService {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;

    public JwtService(JwtEncoder jwtEncoder, JwtProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    public String generateAccessToken(UUID userId, String email, Set<Role> roles) {
        Instant now = Instant.now();
        List<String> roleNames = roles.stream().map(Enum::name).collect(Collectors.toList());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getIssuerUri())
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.getAccessTokenTtlMinutes(), ChronoUnit.MINUTES))
                .claim("email", email)
                .claim("roles", roleNames)
                .build();

        // NimbusJwtEncoder selects RS256 and stamps the kid from the configured RSA JWK.
        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }
}
