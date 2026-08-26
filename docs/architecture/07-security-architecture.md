# Security Architecture

The platform uses **service-level authentication and authorization**, a shared JWT verification component, Kubernetes network boundaries, and isolated infrastructure access.

Security is intentionally distributed: `identity-service` owns authentication and token issuance, while each service independently validates tokens and enforces authorization for its own endpoints.

---

## Table of Contents

1. [Security Architecture Overview](#1-security-architecture-overview)
2. [Authentication Flow](#2-authentication-flow)
3. [JWT Architecture](#3-jwt-architecture)
4. [Authorization & Roles](#4-authorization--roles)
5. [Request Security Flow](#5-request-security-flow)
6. [Trust Boundaries](#6-trust-boundaries)
7. [Internal Service Trust](#7-internal-service-trust)
8. [External Payer Boundary](#8-external-payer-boundary)
9. [Secrets & Configuration](#9-secrets--configuration)
10. [Data Protection](#10-data-protection)
11. [Security Responsibilities](#11-security-responsibilities)
12. [Known Security Gaps](#12-known-security-gaps)
13. [Path to Production](#13-path-to-production)
14. [Related Documentation](#14-related-documentation)

---

# 1. Security Architecture Overview

```text id="sec-overview"
                         INTERNET
                            │
                            │ HTTPS
                            ▼
                 ╔══════════════════════╗
                 ║     NGINX INGRESS    ║
                 ║                      ║
                 ║  Public Boundary     ║
                 ╚══════════╤═══════════╝
                            │
                            │ JWT
                            ▼
              ┌──────────────────────────────┐
              │      APPLICATION LAYER      │
              │                              │
              │ identity-service             │
              │ patient-service              │
              │ doctor-service               │
              │ appointment-service          │
              │ emr-service                  │
              │ billing-service              │
              │ notification-service         │
              │ audit-service                │
              │ analytics-service             │
              │ graphql-gateway               │
              └──────────────┬───────────────┘
                             │
              ┌──────────────┼───────────────┐
              │              │               │
              ▼              ▼               ▼
        ┌───────────┐   ┌───────────┐   ┌───────────┐
        │PostgreSQL │   │   Kafka   │   │   Redis   │
        └───────────┘   └───────────┘   └───────────┘
              │
              ▼
        ┌───────────┐
        │   MinIO   │
        └───────────┘

                             │
                             │ HTTPS / SOAP
                             ▼
                    ┌─────────────────┐
                    │ External Payer  │
                    │ / Clearinghouse │
                    └─────────────────┘
```

The architecture establishes three major security zones:

```text id="security-zones"
┌─────────────────────────────────────────────────────────┐
│  ZONE 1 — PUBLIC                                         │
│                                                         │
│  Internet → NGINX Ingress                               │
└────────────────────────┬────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────┐
│  ZONE 2 — APPLICATION                                    │
│                                                         │
│  Spring Boot services + GraphQL BFF                     │
│  JWT validation + RBAC                                  │
└────────────────────────┬────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────┐
│  ZONE 3 — PRIVATE INFRASTRUCTURE                         │
│                                                         │
│  PostgreSQL / Kafka / RabbitMQ / Redis / MinIO          │
└─────────────────────────────────────────────────────────┘
```

---

# 2. Authentication Flow

`identity-service` is the platform's **sole authentication authority**.

```text id="auth-flow"
┌────────────┐
│    User    │
└─────┬──────┘
      │
      │ Credentials
      ▼
┌────────────┐
│  Frontend  │
└─────┬──────┘
      │
      │ POST /api/v1/auth/login
      ▼
┌──────────────────────┐
│  identity-service    │
│                      │
│  Authenticate user   │
│  Load roles          │
│  Issue JWT           │
└──────────┬───────────┘
           │
           │ JWT Access Token
           ▼
┌──────────────────────┐
│      Frontend        │
└──────────┬───────────┘
           │
           │ Authorization: Bearer <JWT>
           ▼
      ┌─────────────┐
      │   Ingress   │
      └──────┬──────┘
             │
             ▼
      ┌───────────────────┐
      │ Business Service  │
      └─────────┬─────────┘
                │
                ▼
          JwtVerifier
                │
          ┌─────┴─────┐
          ▼           ▼
       Valid       Invalid
          │           │
          ▼           ▼
      RBAC Check     401
          │
     ┌────┴────┐
     ▼         ▼
  Allowed    Denied
     │         │
     ▼         ▼
   200       403
```

A business service **does not call `identity-service` for every request**.

Instead:

```text id="local-jwt"
JWT
 │
 ▼
Business Service
 │
 ▼
JwtVerifier
 │
 ├── Signature
 ├── Expiration
 ├── Claims
 └── Roles
```

This keeps authentication verification local and avoids turning `identity-service` into a request-time bottleneck.

---

# 3. JWT Architecture

The current implementation uses **HS256 symmetric signing**.

```text id="jwt-hs256"
                  SHARED SECRET
                       │
          ┌────────────┴────────────┐
          │                         │
          ▼                         ▼
┌────────────────────┐    ┌────────────────────┐
│ identity-service   │    │ Business Services  │
│                    │    │                    │
│ Sign JWT           │    │ Verify JWT         │
└─────────┬──────────┘    └─────────┬──────────┘
          │                         │
          └────────────┬────────────┘
                       ▼
                      JWT
```

The shared verification implementation lives in `common`:

```text id="jwt-common"
                  common
                    │
                    ▼
              ┌────────────┐
              │ JwtVerifier│
              └─────┬──────┘
                    │
        ┌───────────┼────────────┐
        ▼           ▼            ▼
     Patient      Doctor     Appointment
     Service      Service       Service
        │           │            │
        └───────────┴────────────┘
                    │
                    ▼
              JWT Verification
```

The shared component means the verification mechanism can be migrated centrally when the signing strategy changes.

### Current model

```text
HS256
Shared symmetric secret
        │
        ├── identity-service
        ├── patient-service
        ├── doctor-service
        ├── appointment-service
        ├── emr-service
        ├── billing-service
        ├── notification-service
        ├── audit-service
        ├── analytics-service
        └── graphql-gateway
```

### Target production model

```text
                  Private Signing Key
                         │
                         ▼
                identity-service
                         │
                         │ RS256
                         ▼
                       JWT
                         │
                         ▼
                    Services
                         │
                         ▼
                  Public Key / JWKS
```

RS256/JWKS is a planned production-hardening step rather than the current implementation.

---

# 4. Authorization & Roles

Authentication answers:

```text
"Who are you?"
```

Authorization answers:

```text
"Are you allowed to perform this operation?"
```

The platform currently uses role-based authorization.

```text id="roles"
                         JWT
                          │
                          ▼
                    User Roles
                          │
        ┌─────────────────┼─────────────────┐
        ▼                 ▼                 ▼
     Patient            Doctor             Admin
        │                 │                 │
        ▼                 ▼                 ▼
 Patient APIs       Clinical APIs      Administrative APIs
```

### Available roles

| Role                 | Scope                       |
| -------------------- | --------------------------- |
| `ROLE_PATIENT`       | Patient-facing operations   |
| `ROLE_DOCTOR`        | Clinical operations         |
| `ROLE_NURSE`         | Clinical/support operations |
| `ROLE_BILLING_CLERK` | Billing operations          |
| `ROLE_ADMIN`         | Administrative operations   |

New registrations receive:

```text id="default-role"
Registration
     │
     ▼
ROLE_PATIENT
```

Authorization is enforced **inside each service**.

```text id="endpoint-auth"
Request
   │
   ▼
Controller Endpoint
   │
   ▼
Spring Security
   │
   ▼
Required Role
   │
 ┌─┴──────────────┐
 ▼                ▼
Allowed         Forbidden
 │                │
 ▼                ▼
Business Logic     403
```

There is currently no centralized policy-decision service.

---

# 5. Request Security Flow

A normal authenticated request follows this path:

```text id="request-security"
┌──────────────┐
│    Browser   │
└──────┬───────┘
       │
       │ HTTPS + Bearer JWT
       ▼
┌──────────────┐
│ NGINX        │
│ Ingress      │
└──────┬───────┘
       │
       ▼
┌────────────────────────┐
│ API / GraphQL Service  │
└───────────┬────────────┘
            │
            ▼
       JWT Verification
            │
            ▼
       Authentication
            │
            ▼
       Role Authorization
            │
       ┌────┴─────┐
       ▼          ▼
    Allowed     Denied
       │          │
       ▼          ▼
 Business       403
 Logic
       │
       ▼
 Database / Events
```

The security checks therefore happen before the request reaches protected business operations.

---

# 6. Trust Boundaries

The platform has explicit trust boundaries.

```text id="trust-boundaries"
╔══════════════════════════════════════════════════════════════╗
║                         INTERNET                             ║
║                                                              ║
║   Browser / External Client                                  ║
╚══════════════════════════════╤═══════════════════════════════╝
                               │
                         TRUST BOUNDARY
                               │
                               ▼
╔══════════════════════════════════════════════════════════════╗
║                      NGINX INGRESS                           ║
║                                                              ║
║                 Public Entry Point                           ║
╚══════════════════════════════╤═══════════════════════════════╝
                               │
                               │
                               ▼
┌──────────────────────────────────────────────────────────────┐
│                  KUBERNETES APPLICATION LAYER                 │
│                                                              │
│ identity / patient / doctor / appointment / EMR / billing    │
│ notification / audit / analytics / GraphQL                   │
│                                                              │
│ JWT verification + RBAC                                      │
└──────────────────────────────┬───────────────────────────────┘
                               │
                        INTERNAL TRUST
                               │
                               ▼
┌──────────────────────────────────────────────────────────────┐
│                     PRIVATE INFRASTRUCTURE                    │
│                                                              │
│ PostgreSQL / Kafka / RabbitMQ / Redis / MinIO                │
│                                                              │
│ No external Ingress routes                                   │
└──────────────────────────────────────────────────────────────┘
```

The application infrastructure is not directly exposed through the public edge.

---

# 7. Internal Service Trust

Internal communication has different trust characteristics depending on the protocol.

```text id="internal-trust"
                    Kubernetes Cluster
                           │
            ┌──────────────┼──────────────┐
            │              │              │
            ▼              ▼              ▼
       REST / BFF        gRPC           Kafka
            │              │              │
            ▼              ▼              ▼
      Authenticated    Trusted        Event-based
       request path    network        communication
```

The purpose-built gRPC lookup services currently do **not** carry caller identity.

```text id="grpc-trust"
appointment-service
       │
       │ gRPC
       │
       ▼
patient-service
       │
       ▼
PatientLookup
       │
       ▼
"Does this patient exist?"
```

and:

```text id="doctor-grpc"
appointment-service
       │
       │ gRPC
       ▼
doctor-service
       │
       ▼
DoctorLookup
       │
       ▼
"Does this doctor exist?"
```

These endpoints are not exposed through Ingress.

Therefore the current trust assumption is:

```text id="grpc-boundary"
Outside Cluster
      │
      X
      │
      └──── gRPC not exposed

Inside Cluster
      │
      ▼
Purpose-built gRPC lookup
```

This is a deliberate current-state trade-off.

It becomes insufficient when internal calls require **per-caller identity or authorization**, which is why the EMR referral workflow uses REST instead.

---

# 8. External Payer Boundary

`billing-service` is the platform's main outbound integration boundary.

```text id="payer-security"
┌─────────────────────┐
│   billing-service   │
└──────────┬──────────┘
           │
           │ SOAP
           │
           ▼
┌────────────────────────────┐
│ External Payer /           │
│ Clearinghouse              │
└────────────────────────────┘
```

The integration is isolated from the core event-driven workflow.

```text id="payer-isolation"
                    Kafka
                      │
                      ▼
              billing-service
                      │
                      │
                      ├──── Normal billing processing
                      │
                      │
                      └──── Eligibility endpoint
                                │
                                ▼
                         External Payer
```

The payer integration uses:

```text id="payer-protection"
External Payer
     │
     ▼
Short Connect Timeout
     │
     ▼
Short Read Timeout
     │
     ▼
Circuit Breaker
     │
 ┌───┴────┐
 ▼        ▼
Success  Failure
 │        │
 ▼        ▼
Result   BusinessException
```

Current timeout configuration:

* Connect timeout: **2 seconds**
* Read timeout: **3 seconds**
* Circuit breaker: `payerEligibility`

The goal is to prevent an unavailable external payer from blocking Kafka consumers or the core appointment workflow.

---

# 9. Secrets & Configuration

The current implementation obtains security configuration through application configuration.

```text id="secrets-current"
Configuration
     │
     ▼
JwtProperties
     │
     ▼
Shared JWT Secret
     │
 ┌───┼────────────┬────────────┐
 ▼   ▼            ▼            ▼
Svc  Svc          Svc          Svc
```

For production, sensitive configuration should move behind a dedicated secrets-management solution.

```text id="secrets-target"
                  Secrets Manager
                        │
            ┌───────────┼───────────┐
            ▼           ▼           ▼
          JWT Key    DB Secrets   Broker Secrets
            │           │           │
            ▼           ▼           ▼
        Services     Services     Services
```

This removes sensitive values from ordinary application configuration and makes rotation manageable.

---

# 10. Data Protection

Security is not limited to authentication.

The platform also protects sensitive healthcare data at rest.

```text id="data-protection"
                  PHI / Sensitive Data
                          │
                          ▼
                  Application Service
                          │
                          ▼
                 Encryption Boundary
                          │
                          ▼
                 AES-256-GCM Encryption
                          │
                          ▼
                    PostgreSQL
```

Current protection includes **field-level encryption at rest using AES-256-GCM** for protected patient data.

The broader data architecture remains:

```text id="secure-data"
                 Application
                      │
       ┌──────────────┼───────────────┐
       ▼              ▼               ▼
 PostgreSQL         MinIO           Redis
       │              │               │
 Transactional     Documents        Cache
     Data             │
                      ▼
                 Protected Data
```

Kafka and RabbitMQ remain internal infrastructure and are not directly exposed through the public edge.

---

# 11. Security Responsibilities

Security ownership is distributed by responsibility.

```text id="security-responsibility"
┌────────────────────────┬──────────────────────────────────────┐
│ Component              │ Security Responsibility              │
├────────────────────────┼──────────────────────────────────────┤
│ identity-service       │ Authentication + token issuance      │
│ common/JwtVerifier     │ JWT signature/claim verification     │
│ Business services      │ Endpoint authorization + RBAC        │
│ NGINX Ingress          │ Public network boundary              │
│ Kubernetes             │ Runtime/network isolation            │
│ PostgreSQL             │ Persistent application data          │
│ MinIO                  │ Document/object storage              │
│ Kafka                  │ Internal event transport             │
│ RabbitMQ               │ Internal notification transport      │
│ billing-service        │ External payer boundary              │
└────────────────────────┴──────────────────────────────────────┘
```

There is deliberately no central authorization service today.

```text id="distributed-authz"
                    JWT
                     │
        ┌────────────┼─────────────┐
        ▼            ▼             ▼
    Patient       Doctor      Appointment
     Service       Service       Service
        │            │             │
        ▼            ▼             ▼
      RBAC          RBAC          RBAC
```

This allows each service to protect its own domain operations.

---

# 12. Known Security Gaps

The current architecture has several explicitly identified production-hardening areas.

### Shared HMAC signing key

```text
Current

identity-service
       │
       ▼
   HS256 Secret
       │
       ├──► Service A
       ├──► Service B
       ├──► Service C
       └──► ...
```

A compromise of the shared secret could allow token forgery.

The target architecture is:

```text
Target

Private Key
    │
    ▼
identity-service
    │
    ▼
   JWT
    │
    ▼
Public JWKS
    │
    ├──► Service A
    ├──► Service B
    └──► Service C
```

### Secrets management

Current configuration is suitable for development but should be replaced with managed secret storage for production.

### Kubernetes network policies

Current cluster communication relies primarily on Kubernetes' default namespace/network behavior.

```text
Current

Pod A ───────────────► Pod B
Pod A ───────────────► Pod C
Pod B ───────────────► Pod C
```

A hardened deployment should explicitly define allowed communication paths:

```text
Target

Pod A ───────────────► Pod B
        allowed

Pod A ────────X──────► Pod C
        denied
```

### Internal gRPC identity

Current gRPC lookup contracts are trusted based on cluster reachability rather than caller authentication.

A future zero-trust design can introduce service identity and authenticated service-to-service communication.

---

# 13. Path to Production

The security hardening direction is:

```text id="production-security"
CURRENT
   │
   ▼
HS256 + Shared Secret
   │
   ▼
Development Configuration
   │
   ▼
Default Kubernetes Network Trust
   │
   │
   ├───────────────────────────────┐
   ▼                               ▼
RS256 / JWKS                  Secrets Manager
   │                               │
   ▼                               ▼
Public-Key Verification       Managed Secrets
   │                               │
   └───────────────┬───────────────┘
                   ▼
          Kubernetes Network Policies
                   │
                   ▼
          Authenticated Service Identity
                   │
                   ▼
             Hardened Platform
```

Future security improvements can include:

* RS256/JWKS asymmetric token verification
* managed secrets and automated secret rotation
* Kubernetes `NetworkPolicy`
* authenticated service-to-service communication
* stronger internal identity controls
* centralized policy management where domain complexity requires it
* additional audit/security monitoring
* production-grade key management

These should be introduced incrementally rather than conflated with the current implementation.

---

# 14. Related Documentation

| Document                                                                               | Focus                                       |
| -------------------------------------------------------------------------------------- | ------------------------------------------- |
| [System Context](01-system-context.md)                                                 | External actors and system boundaries       |
| [Container Architecture](02-container-architecture.md)                                 | Runtime containers and network entry points |
| [Service Architecture](03-service-architecture.md)                                     | Service responsibilities and ownership      |
| [Communication Architecture](04-communication-architecture.md)                         | REST, gRPC, Kafka, RabbitMQ and SOAP        |
| [Event Topology](05-event-topology.md)                                                 | Event-driven communication                  |
| [Data Architecture](06-data-architecture.md)                                           | Data ownership and persistence              |
| [ADR-0001 — API Gateway / Ingress](../adr/0001-api-gateway-ingress.md)                 | External access boundary                    |
| [ADR-0003 — SOAP Payer Eligibility](../adr/0003-soap-payer-eligibility-integration.md) | External payer isolation                    |
| [Path to Production](../../README.md#path-to-production)                               | Production-hardening roadmap                |

> **Security Architecture:** Authentication is centralized at `identity-service`, while JWT verification and authorization are distributed across the business services. NGINX Ingress forms the public boundary, application services form the protected application layer, and databases/brokers/object storage remain private infrastructure. The current design is explicit about its trust assumptions—particularly HS256 shared-secret JWTs and unauthenticated internal gRPC lookups—while keeping the path toward RS256/JWKS, managed secrets, network policies, and stronger service identity clear.
