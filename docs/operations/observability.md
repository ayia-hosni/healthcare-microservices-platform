# 📊 Observability

> Part of [Operations](README.md).

Observability is treated as a core part of the platform.

```text
                                    Application Services
                                            │
                  ┌─────────────────────────┼─────────────────────────┐
                  │                         │                         │
                  ▼                         ▼                         ▼
               Metrics                    Traces                    Health
                  │                         │                         │
                  ▼                         ▼                         ▼
             Prometheus                  Zipkin               Spring Actuator
                  │                                                   │
                  ▼                                                   ▼
               Grafana                                      Kubernetes Probes
                                                          Liveness / Readiness
```

The observability stack is intended to help answer questions such as:

* Which service is producing errors?
* Where is request latency increasing?
* Are consumers processing events?
* Which dependencies are unavailable?
* Is a service approaching its resource limits?

The designed OpenTelemetry evolution is tracked in
[`../../PROGRESS.md`](../../PROGRESS.md#designed-not-yet-built).

Prometheus/Grafana/Zipkin are the cloud-independent application observability stack, running
the same way whether the platform is on Docker Compose, Minikube, or a cloud Kubernetes
cluster. Infrastructure-level logs/metrics are cloud-specific — see
[`../cloud/azure/observability.md`](../cloud/azure/observability.md) (Log Analytics) and
[`../cloud/aws/observability.md`](../cloud/aws/observability.md) (CloudWatch).
