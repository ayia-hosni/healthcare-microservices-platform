package com.healthplatform.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;

/**
 * Verifies access tokens issued by identity-service. Every downstream service (patient,
 * doctor, appointment, EMR, billing, notification, audit, analytics) uses this SAME class
 * against the SAME shared secret so a token minted once is honored everywhere — this is
 * what lets each service be independently deployable while still trusting a single
 * authentication authority. In production the secret is replaced by asymmetric verification
 * (RS256 public key or a JWKS endpoint) so downstream services never hold key material
 * capable of *issuing* tokens, only verifying them; HS256 + shared secret is used here for
 * learning simplicity.
 */
public class JwtVerifier {

    private final SecretKey key;

    public JwtVerifier(String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes());
    }

    public Claims verify(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
