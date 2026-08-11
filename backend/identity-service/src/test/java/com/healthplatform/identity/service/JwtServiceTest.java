package com.healthplatform.identity.service;

import com.healthplatform.identity.config.JwtProperties;
import com.healthplatform.identity.domain.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real (non-mocked) unit tests for JwtService: it has no collaborators worth mocking, so
 * these exercise the actual JJWT signing/parsing path -- claim shape, expiry enforcement,
 * and signature validation.
 */
class JwtServiceTest {

    private static final String SECRET = "test-secret-value-at-least-32-bytes-long!!";

    private JwtProperties props;
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        props = new JwtProperties();
        props.setSecret(SECRET);
        props.setAccessTokenTtlMinutes(15);
        props.setRefreshTokenTtlDays(30);
        jwtService = new JwtService(props);
    }

    @Test
    void generatedTokenCarriesSubjectEmailAndRoles() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateAccessToken(userId, "aya@example.com", EnumSet.of(Role.ROLE_PATIENT, Role.ROLE_DOCTOR));

        Claims claims = jwtService.parseAndValidate(token);

        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get("email", String.class)).isEqualTo("aya@example.com");
        @SuppressWarnings("unchecked")
        List<String> roles = claims.get("roles", List.class);
        assertThat(roles).containsExactlyInAnyOrder("ROLE_PATIENT", "ROLE_DOCTOR");
    }

    @Test
    void generatedTokenExpiresAccordingToConfiguredTtl() {
        props.setAccessTokenTtlMinutes(15);
        jwtService = new JwtService(props);
        Instant before = Instant.now();

        String token = jwtService.generateAccessToken(UUID.randomUUID(), "aya@example.com", EnumSet.of(Role.ROLE_PATIENT));
        Claims claims = jwtService.parseAndValidate(token);

        Instant expiry = claims.getExpiration().toInstant();
        // Expiry should land ~15 minutes after issuance, allowing slack for test execution time.
        assertThat(expiry).isBetween(before.plusSeconds(14 * 60), before.plusSeconds(16 * 60));
    }

    @Test
    void parseAndValidateRejectsExpiredToken() {
        props.setAccessTokenTtlMinutes(-1); // already-expired token
        jwtService = new JwtService(props);

        String expiredToken = jwtService.generateAccessToken(UUID.randomUUID(), "aya@example.com", EnumSet.of(Role.ROLE_PATIENT));

        assertThatThrownBy(() -> jwtService.parseAndValidate(expiredToken))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void parseAndValidateRejectsTokenSignedWithDifferentKey() {
        SecretKey otherKey = Keys.hmacShaKeyFor("a-completely-different-secret-key-of-32-bytes!!".getBytes());
        String foreignToken = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("email", "intruder@example.com")
                .claim("roles", List.of("ROLE_ADMIN"))
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(900)))
                .signWith(otherKey)
                .compact();

        assertThatThrownBy(() -> jwtService.parseAndValidate(foreignToken))
                .isInstanceOf(SignatureException.class);
    }

    @Test
    void parseAndValidateRejectsMalformedToken() {
        assertThatThrownBy(() -> jwtService.parseAndValidate("not-a-jwt-at-all"))
                .isInstanceOf(io.jsonwebtoken.MalformedJwtException.class);
    }
}
