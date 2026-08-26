# Azure Observability

> Part of [Azure Architecture](README.md). Covers metrics, logging, and distributed tracing —
> Azure Monitor/Log Analytics alongside the cloud-independent Prometheus/Grafana/Zipkin stack.

---

## Table of Contents

- [Layered Observability](#layered-observability)
- [Metrics](#metrics)
- [Logging](#logging)
- [Distributed Tracing](#distributed-tracing)

---

# Layered Observability

The platform uses a layered observability architecture.

```text
                    Observability
                         │
          ┌──────────────┼──────────────┐
          ▼              ▼              ▼
        Logs          Metrics         Traces
          │              │              │
          ▼              ▼              ▼
    Log Analytics    Prometheus       Zipkin
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

---

# Logging

Centralized logging provides:

* structured application logs
* Kubernetes logs
* infrastructure logs
* searchable incident history
* correlation identifiers

Logs should use structured JSON where possible.

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

This makes distributed incident investigation significantly easier.
