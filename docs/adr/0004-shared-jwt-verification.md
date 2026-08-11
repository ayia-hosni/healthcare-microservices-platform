# ADR-004: Shared JWT verification via the common module

## Status
Accepted

## Context
Every business service needs to authenticate incoming requests. Duplicating JWT parsing
logic across 8 services invites drift (one service quietly accepting an expired token
because its copy of the logic diverged).

## Decision
`common.security.JwtVerifier` and `common.security.ResourceServerJwtFilter` live in the
shared `common` module and are reused, unmodified, by every business service's
`SecurityConfig`. Only identity-service also depends on `JwtService` (which additionally
knows how to *issue* tokens) — no other service can mint tokens, only verify them.

In this learning build all services share one HMAC secret (`app.jwt.secret`) for simplicity.
Production would switch to RS256 with identity-service holding the private key and every
other service verifying against its public key (or a JWKS endpoint), so a compromised
business service can never forge tokens for another user.

## Consequences
+ One place to fix a JWT bug, not nine.
+ Consistent authorization behavior across the platform.
- The `common` module now carries a Spring Security dependency, which is a reasonable
  trade for services that all need it, but would be a smell if `common` started
  accumulating unrelated framework dependencies for the sake of one consumer.
