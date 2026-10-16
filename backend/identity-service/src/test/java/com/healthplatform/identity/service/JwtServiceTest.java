package com.healthplatform.identity.service;

import com.healthplatform.identity.config.JwtProperties;
import com.healthplatform.identity.domain.Role;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real (non-mocked) unit tests for JwtService: exercises the actual RS256 signing path and
 * verifies the output with a Nimbus decoder — claim shape, issuer, expiry, and that a token
 * signed by a different key is rejected.
 */
class JwtServiceTest {

    private static final String ISSUER = "https://issuer.test";

    private RSAKey rsaKey;
    private JwtProperties props;
    private JwtService jwtService;
    private JwtDecoder decoder;

    @BeforeEach
    void setUp() throws Exception {
        rsaKey = generateRsaKey();
        props = new JwtProperties();
        props.setIssuerUri(ISSUER);
        props.setAccessTokenTtlMinutes(15);
        jwtService = new JwtService(new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey))), props);

        NimbusJwtDecoder d = NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();
        d.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
        decoder = d;
    }

    @Test
    void generatedTokenCarriesSubjectEmailRolesAndIssuer() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateAccessToken(userId, "aya@example.com",
                EnumSet.of(Role.ROLE_PATIENT, Role.ROLE_DOCTOR));

        Jwt jwt = decoder.decode(token);

        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsString("email")).isEqualTo("aya@example.com");
        assertThat(jwt.getClaimAsStringList("roles"))
                .containsExactlyInAnyOrder("ROLE_PATIENT", "ROLE_DOCTOR");
        assertThat(jwt.getIssuer().toString()).isEqualTo(ISSUER);
        assertThat(jwt.getHeaders().get("alg")).isEqualTo("RS256");
        assertThat(jwt.getHeaders().get("kid")).isEqualTo(rsaKey.getKeyID());
    }

    @Test
    void generatedTokenExpiresAccordingToConfiguredTtl() {
        props.setAccessTokenTtlMinutes(15);
        Instant before = Instant.now();

        String token = jwtService.generateAccessToken(UUID.randomUUID(), "aya@example.com",
                EnumSet.of(Role.ROLE_PATIENT));
        Jwt jwt = decoder.decode(token);

        assertThat(jwt.getExpiresAt())
                .isBetween(before.plusSeconds(14 * 60), before.plusSeconds(16 * 60));
    }

    @Test
    void tokenSignedWithADifferentKeyIsRejected() throws Exception {
        RSAKey otherKey = generateRsaKey();
        JwtService foreign = new JwtService(
                new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(otherKey))), props);
        String foreignToken = foreign.generateAccessToken(UUID.randomUUID(), "intruder@example.com",
                EnumSet.of(Role.ROLE_ADMIN));

        assertThatThrownBy(() -> decoder.decode(foreignToken)).isInstanceOf(JwtException.class);
    }

    @Test
    void expiredTokenIsRejected() {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey)));
        Instant past = Instant.now().minusSeconds(1200);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(UUID.randomUUID().toString())
                .issuedAt(past)
                .expiresAt(past.plusSeconds(300)) // expired 15 minutes ago
                .claim("roles", List.of("ROLE_PATIENT"))
                .build();
        String expired = encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();

        assertThatThrownBy(() -> decoder.decode(expired)).isInstanceOf(JwtException.class);
    }

    private static RSAKey generateRsaKey() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        var pair = gen.generateKeyPair();
        return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate())
                .keyIDFromThumbprint()
                .build();
    }
}
