# AWS Observability

> Part of [AWS Architecture](README.md). 🏗️ Designed, not deployed — see the banner there.
> Covers metrics, logging, and distributed tracing — CloudWatch alongside the cloud-independent
> Prometheus/Grafana/Zipkin stack.

---

## Table of Contents

- [Layered Observability](#layered-observability)
- [Metrics](#metrics)
- [Logging](#logging)
- [Distributed Tracing](#distributed-tracing)

---

# Layered Observability

```text
                    Observability
                         │
          ┌──────────────┼──────────────┐
          ▼              ▼              ▼
        Logs          Metrics         Traces
          │              │              │
          ▼              ▼              ▼
      CloudWatch      Prometheus       Zipkin
                         │
                         ▼
                      Grafana
```

---

# Metrics

Prometheus collects application and infrastructure metrics such as:

* HTTP request rate
* HTTP latency
* error rate
* JVM memory
* JVM threads
* database connections
* Kafka consumer lag
* Redis usage
* Kubernetes resource utilization

CloudWatch Container Insights adds cluster- and node-level infrastructure
metrics alongside these application metrics.

---

# Logging

Centralized logging provides structured application logs, Kubernetes logs,
infrastructure logs, searchable incident history, and correlation
identifiers. Logs should use structured JSON where possible, shipped to
CloudWatch Logs.

---

# Distributed Tracing

Tracing follows requests across service boundaries.

```text
Frontend
   │
   ▼
GraphQL Gateway
   │
   ▼
Appointment Service
   │
   ├──────────► Patient Service
   │
   ├──────────► Billing Service
   │
   └──────────► Notification Service
```

Correlation IDs connect:

```text
HTTP Request
     │
     ├── Logs
     ├── Trace
     └── Events
```

This makes distributed incident investigation significantly easier. The
application observability stack (Prometheus, Grafana, Zipkin) stays
identical to the Azure deployment, which keeps monitoring cloud-independent
even though the underlying infrastructure metrics/logs move from Log
Analytics to CloudWatch.
