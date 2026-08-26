# 🔐 identity-service

> **Authentication, JWT issuance, and account management for the Healthcare Platform.**

**Port:** `8081`
**Database:** `identity_db`
**Database role:** `identity_user`
**Kafka / RabbitMQ:** none — this service has no domain events

The Identity Service is the platform's single point of authentication. Every other service
trusts a JWT this service issued, verified independently using the shared `common`
`JwtVerifier` — there's no per-request call back to identity-service to check a token; it
issues, everyone else validates on their own.

---

## ✨ Capabilities

| Capability                | Description                                                                     |
| --------------------------- | ----------------------------------------------------------------------------- |
| 📝 Self-service registration | New accounts always start as `ROLE_PATIENT` — elevated roles are admin-granted |
| 🔑 Stateless access tokens   | Short-lived HS256 JWTs, never checked against the database on verification    |
| 🔁 Refresh token rotation    | Every refresh revokes the presented token and issues a brand new pair          |
| 🗄️ Server-side revocation    | Refresh tokens are stored hashed, so logout/lock can revoke immediately        |
| 🚪 Correct HTTP semantics    | An explicit `401` entry point, instead of Spring Security's default `403`     |
| 🌐 Locked-down CORS          | Allowed origins come from config, never a wildcard                            |
| 🧹 Scheduled token cleanup   | A daily job purges expired/revoked refresh tokens                             |

---

## 🎯 What It Does

```http
POST /api/v1/auth/register
POST /api/v1/auth/login
POST /api/v1/auth/refresh
POST /api/v1/auth/logout
```

### Registration

Every new account is created with exactly one role:

```java
EnumSet.of(Role.ROLE_PATIENT)
```

Doctor, nurse, billing-clerk, and admin roles are never self-service — they're granted
out-of-band by an admin action. That's a deliberate authorization boundary, enforced in code,
not just policy.

### Password storage

```java
new BCryptPasswordEncoder(12)
```

`RegisterRequest` additionally requires a minimum 12-character password at the validation
layer, before it ever reaches the encoder.

### Token pair issuance

Login, registration, and refresh all funnel through the same `issueTokenPair(user)`:

```text
Access token   — a signed JWT, short TTL, never touches the database again once issued
Refresh token  — a 32-byte random value, returned raw to the client, stored server-side
                 as its SHA-256 hash only (the raw value is never persisted)
```

---

# 🏗️ Architecture

The service has three primary flows: issuing/rotating tokens, verifying them on every
request, and cleaning up what's left behind.

```text
══════════════════════════════════════════════════════════════════════════════════════════════
                                   IDENTITY SERVICE
══════════════════════════════════════════════════════════════════════════════════════════════


    ┌────────────────────────────────────────────────────────────────────────────────────┐
    │                      1. REGISTER / LOGIN — ISSUING A TOKEN PAIR                    │
    └────────────────────────────────────────────────────────────────────────────────────┘

        Client
          │
          │ POST /register  or  POST /login
          ▼
    ┌─────────────────────────────────────┐
    │            AuthController            │
    └──────────────────┬──────────────────┘
                       │
                       ▼
    ┌──────────────────────────────────────────────┐
    │                 AuthService                   │
    │                                                │
    │  register(): reject if email taken,            │
    │              BCrypt(12) the password,           │
    │              save User{roles=[ROLE_PATIENT]}    │
    │                                                │
    │  login():    verify locked/disabled/password    │
    └──────────────────┬───────────────────────────┘
                       │
                       ▼
    ┌──────────────────────────────────────────────┐
    │              issueTokenPair(user)              │
    └──────────────────┬───────────────────────────┘
                       │
          ┌─────────────┴─────────────┐
          ▼                           ▼
    ┌───────────────┐      ┌────────────────────────────────┐
    │   JwtService   │      │   generateOpaqueToken() +      │
    │                │      │   SHA-256 hash it               │
    │  HS256, claims:│      │                                  │
    │  sub, email,   │      │   RefreshToken saved:            │
    │  roles         │      │   (userId, tokenHash, expiresAt) │
    └───────┬───────┘      └────────────────┬────────────────┘
            │                                │
            ▼                                ▼
      identity_db.users            identity_db.refresh_tokens
                                    (raw token never persisted)
                       │
                       ▼
    ┌──────────────────────────────────────────────┐
    │   AuthResponse(accessToken, refreshToken,     │
    │                accessTokenExpiresInSeconds)   │
    └──────────────────┬───────────────────────────┘
                       │
                       ▼
                    Client


══════════════════════════════════════════════════════════════════════════════════════════════


    ┌────────────────────────────────────────────────────────────────────────────────────┐
    │                       2. REFRESH — ROTATE, DON'T REUSE                            │
    └────────────────────────────────────────────────────────────────────────────────────┘


        Client
          │
          │ POST /refresh { refreshToken }
          ▼
    ┌─────────────────────────────────────┐
    │            AuthController            │
    └──────────────────┬──────────────────┘
                       │
                       ▼
    ┌──────────────────────────────────────────────┐
    │  AuthService.refresh(rawRefreshToken)          │
    │                                                │
    │  1. hash the presented token                   │
    │  2. look up by hash, require isValid()         │
    │     (not revoked AND not expired)              │
    │  3. stored.revoke() — the OLD token is now dead│
    │  4. issueTokenPair(user) — a brand NEW pair    │
    └──────────────────┬───────────────────────────┘
                       │
                       ▼
          Old refresh token: revoked = true
          New refresh token: freshly issued
                       │
                       ▼
          If the old (now-revoked) token is ever
          presented again — e.g. it was stolen and
          replayed — refresh fails outright, which
          is the detection signal for token theft.


══════════════════════════════════════════════════════════════════════════════════════════════


    ┌────────────────────────────────────────────────────────────────────────────────────┐
    │                  3. JWT VERIFICATION — EVERY AUTHENTICATED REQUEST                │
    └────────────────────────────────────────────────────────────────────────────────────┘


        Any request with an Authorization header
                       │
                       ▼
    ┌──────────────────────────────────────────────┐
    │        JwtAuthenticationFilter                 │
    │        (OncePerRequestFilter, added before     │
    │         UsernamePasswordAuthenticationFilter)  │
    │                                                │
    │  header starts with "Bearer "?                 │
    └──────────────────┬───────────────────────────┘
                       │
                       ▼
    ┌──────────────────────────────────────────────┐
    │        JwtService.parseAndValidate(token)      │
    │        — signature + expiry check, HS256       │
    └──────────────────┬───────────────────────────┘
                       │
              ┌─────────┴─────────┐
              ▼                   ▼
           valid               invalid/expired
              │                   │
              ▼                   ▼
    SecurityContext set    SecurityContext cleared
    (roles ──► GrantedAuthority)   (request proceeds unauthenticated)
              │                   │
              └─────────┬─────────┘
                       ▼
    ┌──────────────────────────────────────────────┐
    │              SecurityConfig                    │
    │                                                │
    │  /api/v1/auth/**, /actuator/health/** ──► open │
    │  everything else ──► authenticated()           │
    │  no auth header on a protected route ──► 401   │
    │  (explicit HttpStatusEntryPoint — Spring        │
    │   Security's undeclared default is 403,         │
    │   which is wrong REST semantics here)           │
    └────────────────────────────────────────────────┘


══════════════════════════════════════════════════════════════════════════════════════════════


    ┌────────────────────────────────────────────────────────────────────────────────────┐
    │                    4. SCHEDULED CLEANUP — REFRESH TOKEN TABLE                     │
    └────────────────────────────────────────────────────────────────────────────────────┘


              ┌────────────────────────────────────────┐
              │  @Scheduled(cron = "0 0 3 * * *")      │
              │  daily at 03:00                        │
              └──────────────────┬─────────────────────┘
                                 │
                                 ▼
                    ┌─────────────────────────┐
                    │     TokenCleanupJob     │
                    └────────────┬────────────┘
                                 │
                                 ▼
        RefreshTokenRepository.findAll(), filter !isValid()
        (expired OR revoked)
                                 │
                                 ▼
                       delete each one
                                 │
                                 ▼
                   log.info(count removed)
```

---

# 📦 Package Structure

```text
identity-service/
└── src/main/java/.../identity/
    │
    ├── config/
    │   ├── JwtProperties
    │   └── SecurityConfig
    │
    ├── security/
    │   └── JwtAuthenticationFilter
    │
    ├── web/
    │   ├── AuthController
    │   ├── GlobalExceptionHandler
    │   └── dto/
    │       ├── RegisterRequest
    │       ├── LoginRequest
    │       ├── RefreshRequest
    │       └── AuthResponse
    │
    ├── service/
    │   ├── AuthService
    │   └── JwtService
    │
    ├── scheduler/
    │   └── TokenCleanupJob
    │
    ├── repository/
    │   ├── UserRepository
    │   └── RefreshTokenRepository
    │
    └── domain/
        ├── User
        ├── Role
        └── RefreshToken
```

---

# 🔑 Access vs. Refresh: Two Different Trust Models

## Access tokens are stateless, on purpose

```java
Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
```

`JwtService.parseAndValidate` never queries the database — a signature and expiry check is
the entire verification. That's why the access token TTL is kept short: anything requiring
*immediate* revocation (logout, a password change, an account lock) can't touch an
already-issued access token. It can only revoke the *refresh* token, which is what bounds the
blast radius of a leaked access token to its remaining short lifetime.

## Refresh tokens are the opposite: server-side and revocable

```text
Raw refresh token   ──► returned to the client once, never stored
SHA-256(raw token)  ──► what's actually persisted, in refresh_tokens.token_hash
```

Because the hash — not the raw value — is what's stored, a database leak doesn't hand out
usable refresh tokens. And because each row has a `revoked` flag `AuthService` can flip
immediately, logout and refresh-rotation both take effect the instant they're called, with no
TTL to wait out.

## Rotation as theft detection

Every `POST /refresh` revokes the token it was handed and issues a completely new pair —
never reuses or extends the presented token. The direct consequence: if a refresh token is
ever exfiltrated and an attacker uses it, the legitimate client's *next* refresh attempt fails
(their token was already revoked by the attacker's use), which is the signal that something
is wrong — this design makes token replay detectable instead of silently permitted.

---

# 🌐 CORS and the 401/403 Fix

Two defensive details in `SecurityConfig` that are easy to miss reading the code casually:

**CORS is locked to explicit origins, never `*`.** Allowed origins come from
`CorsProperties` (`app.cors.allowed-origins`, env override `CORS_ALLOWED_ORIGINS`) — the same
property every other service in the platform reads, so adding a new frontend origin is one
environment variable change, not a hunt through every service's `SecurityConfig`.

**Unauthenticated requests return `401`, not Spring Security's undeclared default of `403`.**
Without an explicit entry point, `Http403ForbiddenEntryPoint` is what Spring Security falls
back to for "no credentials presented" — which is the wrong status for a token-issuing auth
API and breaks any client branching on `401` vs `403`. An explicit
`HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)` fixes that; an authenticated request that
simply lacks the required *role* still correctly falls through to `403`.

---

# 🚀 Where This Is Headed

Signing moves from a shared HS256 secret to RS256, with identity-service holding the private
key and publishing a JWKS endpoint — downstream services verify against the public key
instead of needing key material capable of issuing tokens. Key rotation and external secret
management come with it, so the signing secret stops being a value every service's config has
to know. See [`../../PROGRESS.md`](../../PROGRESS.md#designed-not-yet-built) for where this
sits relative to the rest of the platform's security roadmap.

---

See [`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) for how this fits into the platform, and
[`../../RELIABILITY.md`](../../RELIABILITY.md) for the wider authentication model.
