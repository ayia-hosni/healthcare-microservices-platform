# Observability Architecture

The platform uses **Spring Boot Actuator, Micrometer, Prometheus, Grafana, Kubernetes health probes, and Docker health checks** to provide application and infrastructure observability.

The current observability design separates three concerns:

```text
                    OBSERVABILITY
                         │
          ┌──────────────┼──────────────┐
          ▼              ▼              ▼
       Metrics         Health         Tracing
          │              │              │
          ▼              ▼              ▼
     Prometheus       Actuator        Zipkin
          │              │              │
          ▼              ▼              ▼
       Grafana       Kubernetes       Future
                     / Docker        integration
```

Metrics and health monitoring are implemented and wired into the platform. Distributed tracing is provisioned but not yet connected to the application services.

---

## Table of Contents

1. [Observability Overview](#1-observability-overview)
2. [Observability Architecture](#2-observability-architecture)
3. [Application Metrics](#3-application-metrics)
4. [Prometheus](#4-prometheus)
5. [Grafana](#5-grafana)
6. [Health Monitoring](#6-health-monitoring)
7. [Container Health](#7-container-health)
8. [Kubernetes Probes](#8-kubernetes-probes)
9. [Distributed Tracing](#9-distributed-tracing)
10. [Observability Data Flow](#10-observability-data-flow)
11. [What Can Be Observed Today](#11-what-can-be-observed-today)
12. [Current Observability Gaps](#12-current-observability-gaps)
13. [Production Observability Direction](#13-production-observability-direction)
14. [Related Documentation](#14-related-documentation)

---

# 1. Observability Overview

The observability stack is centered around the Spring Boot services.

```text
┌─────────────────────────────────────────────────────────────┐
│                  HEALTHCARE PLATFORM                        │
│                                                             │
│  identity     patient      doctor      appointment          │
│  EMR          billing      notification                    │
│  audit        analytics    graphql-gateway                  │
└──────────────────────────┬──────────────────────────────────┘
                           │
              ┌────────────┼────────────┐
              │            │            │
              ▼            ▼            ▼
          /actuator   /actuator     tracing
          /prometheus    /health
              │            │            │
              ▼            ▼            ▼
          Prometheus    Actuator      Zipkin
              │            │
              ▼            ▼
           Grafana     Kubernetes /
                       Docker
```

This gives the platform visibility into:

* application metrics
* service health
* container health
* Kubernetes readiness
* Kubernetes liveness
* resource utilization
* application latency
* application errors

---

# 2. Observability Architecture

```text
                         SPRING BOOT SERVICES
                                  │
             ┌────────────────────┼────────────────────┐
             │                    │                    │
             │                    │                    │
             ▼                    ▼                    ▼
    /actuator/prometheus   /actuator/health       Trace Data
             │                    │                    │
             ▼                    ▼                    ▼
        ┌───────────┐       ┌───────────┐       ┌───────────┐
        │ Prometheus│       │  Actuator │       │   Zipkin  │
        └─────┬─────┘       └─────┬─────┘       └───────────┘
              │                   │
              ▼                   │
        ┌───────────┐             │
        │  Grafana  │             │
        └───────────┘             │
                                  │
                         ┌────────┴────────┐
                         ▼                 ▼
                    Kubernetes          Docker
                    Probes              Healthcheck
```

The architecture intentionally keeps:

```text
Metrics  → Prometheus → Grafana
Health   → Actuator  → Kubernetes / Docker
Tracing  → Zipkin     → Future application integration
```

as separate observability paths.

---

# 3. Application Metrics

All ten Spring Boot services expose Prometheus-compatible metrics.

```text
                     10 Spring Boot Services
                              │
        ┌─────────────────────┼─────────────────────┐
        │                     │                     │
        ▼                     ▼                     ▼
     Actuator              Micrometer          Prometheus
        │                     │                     │
        └─────────────────────┴─────────────────────┘
                              │
                              ▼
                   /actuator/prometheus
```

The services are:

```text
identity-service
patient-service
doctor-service
appointment-service
emr-service
billing-service
notification-service
audit-service
analytics-service
graphql-gateway
```

Each service includes:

```text
Spring Boot
    │
    ├── Actuator
    │
    └── Micrometer Prometheus Registry
              │
              ▼
       /actuator/prometheus
```

This means metrics are exposed consistently across the platform rather than implemented separately inside each service.

---

# 4. Prometheus

Prometheus periodically scrapes each service.

```text
┌──────────────────┐
│ identity-service │
└────────┬─────────┘
         │
         │ /actuator/prometheus
         ▼
┌──────────────────┐
│                  │
│                  │
│    PROMETHEUS    │
│                  │
│                  │
└───────┬──────────┘
        │
        │ Metrics
        ▼
┌──────────────────┐
│      Grafana     │
└──────────────────┘
```

The complete scrape model is:

```text
                  Prometheus
                      │
       ┌──────────────┼───────────────┐
       │              │               │
       ▼              ▼               ▼
   identity       patient          doctor
       │              │               │
       ▼              ▼               ▼
   appointment       EMR           billing
       │              │               │
       └──────────────┼───────────────┘
                      │
              notification
              audit
              analytics
              graphql
```

The Prometheus configuration is located at:

```text
infra/docker/prometheus.yml
```

---

# 5. Grafana

Grafana provides the visualization layer on top of Prometheus.

```text
                 Application Services
                         │
                         ▼
                    Prometheus
                         │
                         │ PromQL
                         ▼
                     Grafana
                         │
              ┌──────────┼──────────┐
              ▼          ▼          ▼
          Service      Request     Resource
          Health       Metrics     Metrics
```

Grafana can be used to correlate:

```text
Service
   │
   ├── Request volume
   ├── Error rate
   ├── Latency
   ├── JVM/runtime metrics
   └── Resource utilization
```

The architecture therefore supports both **service-level monitoring** and **platform-level visibility**.

---

# 6. Health Monitoring

Every Spring Boot service exposes an Actuator health endpoint.

```text
                    Spring Boot Service
                            │
                            ▼
                    Spring Actuator
                            │
                            ▼
                     /actuator/health
                            │
                  ┌─────────┴─────────┐
                  ▼                   ▼
                UP                  DOWN
                  │                   │
                  ▼                   ▼
              Healthy              Unhealthy
```

Health monitoring is consumed by the runtime platform.

```text
                         Actuator
                            │
                            ▼
                     /actuator/health
                            │
                ┌───────────┴───────────┐
                ▼                       ▼
             Docker                Kubernetes
                │                       │
                ▼                       ▼
          Container state       Liveness / Readiness
```

---

# 7. Container Health

Docker Compose uses health checks against the application health endpoint.

```text
┌───────────────────┐
│ Docker Container  │
│                   │
│ Spring Boot       │
└─────────┬─────────┘
          │
          ▼
 /actuator/health
          │
     ┌────┴────┐
     ▼         ▼
    UP        DOWN
     │         │
     ▼         ▼
 Healthy    Unhealthy
```

An unhealthy container can therefore be identified by the Docker runtime.

This provides a consistent health mechanism between local Docker development and Kubernetes deployment.

---

# 8. Kubernetes Probes

Kubernetes uses the same Actuator health layer for runtime orchestration.

```text
                       Kubernetes
                            │
             ┌──────────────┴──────────────┐
             │                             │
             ▼                             ▼
       Liveness Probe                Readiness Probe
             │                             │
             ▼                             ▼
      "Is the process              "Can it receive
           alive?"                    traffic?"
             │                             │
             └──────────────┬──────────────┘
                            ▼
                       /actuator/health
                            │
                     ┌──────┴──────┐
                     ▼             ▼
                    UP            DOWN
                     │             │
                     ▼             ▼
                 Continue       Restart /
                                remove from
                                traffic
```

This gives the Kubernetes deployment two important guarantees:

### Liveness

Detects a service that is alive at the process level but no longer functioning correctly.

```text
Liveness failure
       │
       ▼
Kubernetes restarts Pod
```

### Readiness

Determines whether a service should receive traffic.

```text
Readiness failure
       │
       ▼
Pod removed from Service endpoints
       │
       ▼
Traffic stops
```

The same health endpoint therefore participates in both local and Kubernetes operational workflows.

---

# 9. Distributed Tracing

Zipkin is included in the Docker development environment.

```text
                  Zipkin
                    │
                    │
                    X
                    │
              Not connected
                    │
                    ▼
             Spring Services
```

The intended architecture is:

```text
                 Service A
                     │
                     │ Trace
                     ▼
                 Service B
                     │
                     │ Trace
                     ▼
                 Service C
                     │
                     │
                     ▼
                   Zipkin
                     │
                     ▼
              Distributed Trace
```

However, the current services do not yet contain the tracing bridge/export configuration required to produce those traces.

Therefore:

```text
Zipkin Container       ✅ Provisioned
Tracing Infrastructure ✅ Available
Application Tracing    ❌ Not wired
Trace Export           ❌ Not active
```

Zipkin should consequently be treated as **staged observability infrastructure**, not as an active request-path dependency.

---

# 10. Observability Data Flow

The implemented metrics path is:

```text
                    SERVICE
                       │
                       │ Micrometer
                       ▼
              /actuator/prometheus
                       │
                       ▼
                  PROMETHEUS
                       │
                       │ PromQL
                       ▼
                    GRAFANA
                       │
                       ▼
                 ENGINEERING VIEW
```

The implemented health path is:

```text
                    SERVICE
                       │
                       ▼
              /actuator/health
                       │
             ┌─────────┴─────────┐
             ▼                   ▼
          Docker             Kubernetes
             │                   │
             ▼                   ▼
        Container            Pod health
          state             + traffic state
```

The future tracing path is:

```text
Service A ──┐
            │
Service B ──┼──► Trace Export ──► Zipkin
            │
Service C ──┘
```

---

# 11. What Can Be Observed Today

The implemented stack can answer several important operational questions.

### Is a service healthy?

```text
Service
   │
   ▼
Actuator
   │
   ▼
/actuator/health
```

### Which service is experiencing errors?

```text
Services
   │
   ▼
Prometheus
   │
   ▼
Grafana
   │
   ▼
Error / latency metrics
```

### Which service has elevated latency?

```text
Request Metrics
       │
       ▼
   Prometheus
       │
       ▼
    Grafana
       │
       ▼
Service latency
```

### Is a Kubernetes workload ready?

```text
Kubernetes
     │
     ▼
Readiness Probe
     │
     ▼
Actuator
     │
     ▼
Ready / Not Ready
```

### Is a container unhealthy?

```text
Docker
  │
  ▼
Healthcheck
  │
  ▼
Actuator
```

### Is a service approaching its configured resource envelope?

```text
Container / JVM
       │
       ▼
Prometheus
       │
       ▼
Grafana
       │
       ▼
Resource utilization
```

---

# 12. Current Observability Gaps

The current implementation does not yet provide complete distributed observability.

```text
                    OBSERVABILITY
                          │
        ┌─────────────────┼─────────────────┐
        ▼                 ▼                 ▼
      Metrics           Health           Tracing
        │                 │                 │
        ▼                 ▼                 ▼
   Prometheus          Actuator          Zipkin
        │                 │                 │
        ▼                 ▼                 X
     Grafana          K8s/Docker       Not wired
```

### Distributed request tracing

The platform cannot currently answer:

```text
User Request
     │
     ▼
GraphQL
     │
     ▼
Patient Service
     │
     ▼
Database
```

with precise cross-service timing.

Zipkin integration is required for that.

---

### Kafka consumer lag

The current stack does not provide dedicated Kafka consumer-lag monitoring.

```text
Kafka
  │
  ▼
Consumer
  │
  ▼
Processing
```

There is currently no dedicated observability path showing:

```text
Consumer Group
      │
      ├── Current Offset
      ├── Latest Offset
      └── Consumer Lag
```

---

### RabbitMQ metrics

RabbitMQ Management UI is available in the development environment, but RabbitMQ metrics are not currently exported into Prometheus.

The desired model is:

```text
RabbitMQ
   │
   ▼
Metrics Exporter
   │
   ▼
Prometheus
   │
   ▼
Grafana
```

This would make queue depth, retries and DLQ growth visible alongside application metrics.

---

# 13. Production Observability Direction

The observability stack can evolve without changing the application's core architecture.

```text
                       CURRENT
                          │
          ┌───────────────┼────────────────┐
          ▼               ▼                ▼
      Prometheus       Actuator          Zipkin
          │               │                │
          ▼               ▼                X
       Grafana       K8s / Docker       Not wired
                          │
                          │
                          ▼
                    PRODUCTION
                          │
          ┌───────────────┼────────────────┐
          ▼               ▼                ▼
     Metrics           Health           Tracing
          │               │                │
          ▼               ▼                ▼
    Prometheus        Actuator       OpenTelemetry /
       / Grafana      / K8s          Zipkin-compatible
```

The production observability direction includes:

* distributed tracing across REST, gRPC and messaging
* Kafka consumer-lag monitoring
* RabbitMQ queue/retry/DLQ metrics
* centralized structured logging
* correlation/request IDs
* service-level dashboards
* alerting and notification rules
* infrastructure-level monitoring
* Kubernetes workload monitoring
* production-grade retention and storage
* observability integration with the GitOps deployment model

A mature production flow would look like:

```text
                         PLATFORM
                            │
          ┌─────────────────┼──────────────────┐
          │                 │                  │
          ▼                 ▼                  ▼
       Metrics            Logs              Traces
          │                 │                  │
          ▼                 ▼                  ▼
    Prometheus         Log Pipeline       OpenTelemetry
          │                 │                  │
          ▼                 ▼                  ▼
       Grafana         Log Storage        Trace Backend
          │                 │                  │
          └─────────────────┼──────────────────┘
                            ▼
                       Alerting /
                       Operations
```

---

# 14. Related Documentation

| Document                                                       | Focus                                 |
| -------------------------------------------------------------- | ------------------------------------- |
| [System Context](01-system-context.md)                         | External actors and system boundaries |
| [Container Architecture](02-container-architecture.md)         | Runtime containers                    |
| [Service Architecture](03-service-architecture.md)             | Service responsibilities              |
| [Communication Architecture](04-communication-architecture.md) | REST, gRPC, Kafka, RabbitMQ and SOAP  |
| [Event Topology](05-event-topology.md)                         | Kafka event flows                     |
| [Data Architecture](06-data-architecture.md)                   | Persistence and data ownership        |
| [Security Architecture](07-security-architecture.md)           | Authentication and trust boundaries   |
| [Deployment Architecture](08-deployment-architecture.md)       | Kubernetes and deployment model       |
| [ADR-0001](../adr/0001-api-gateway-ingress.md)                 | API gateway / Ingress boundary        |
| [Path to Production](../../README.md#path-to-production)       | Production-hardening direction        |

> **Observability Architecture:** The platform currently has a working metrics and health-monitoring foundation: all ten Spring Boot services expose Actuator and Prometheus metrics, Prometheus collects those metrics, Grafana visualizes them, and the same Actuator health model drives Docker health checks and Kubernetes liveness/readiness probes. Zipkin is provisioned as the future tracing backend but is not yet connected to application code. The next observability layer is therefore distributed tracing, Kafka consumer-lag monitoring, RabbitMQ metrics/DLQ visibility, centralized logging, and production alerting.
