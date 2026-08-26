# ☸️ Kubernetes

> Part of [Infrastructure](README.md). Full manifest-level detail lives in
> [`../../infra/k8s/README.md`](../../infra/k8s/README.md) — this page is the architecture
> summary.

The platform includes Kubernetes manifests organized with Kustomize under `infra/k8s/`
(`base/` + `overlays/dev/`).

```text
                              Internet
                                  │
                                  ▼
                         Load Balancer
                                  │
                                  ▼
╔════════════════════════════════════════════════════════════╗
║                    KUBERNETES CLUSTER                       ║
║                                                              ║
║   ┌────────────────────────────────────────────────────┐    ║
║   │         healthcare-platform namespace              │    ║
║   │                                                    │    ║
║   │               ┌───────────────┐                    │    ║
║   │               │ NGINX Ingress │                    │    ║
║   │               └───────┬───────┘                    │    ║
║   │                       │                            │    ║
║   │      ┌────────────────┼────────────────┐           │    ║
║   │      ▼                ▼                ▼           │    ║
║   │  Identity          Patient          Doctor          │    ║
║   │  Appointment       EMR              Billing          │    ║
║   │  Notification      Audit            Analytics        │    ║
║   │  GraphQL Gateway                                    │    ║
║   └────────────────────────────────────────────────────┘    ║
║                                                              ║
║   ┌────────────────────────────────────────────────────┐    ║
║   │              DATA & MESSAGING                       │    ║
║   │ PostgreSQL │ Redis │ Kafka │ RabbitMQ │ Elasticsearch│    ║
║   │ MinIO                                                │    ║
║   └────────────────────────────────────────────────────┘    ║
║                                                              ║
║   ┌────────────────────────────────────────────────────┐    ║
║   │               OBSERVABILITY                         │    ║
║   │ Prometheus │ Grafana │ Zipkin │ Actuator             │    ║
║   └────────────────────────────────────────────────────┘    ║
╚════════════════════════════════════════════════════════════╝
```

The Kubernetes configuration demonstrates Deployments, Services, ConfigMaps, Secrets, health
probes, resource requests/limits, environment-specific overlays, and container image
configuration.

## Kubernetes with Minikube

For the exact commands — starting Minikube, deploying, rebuilding a service's image, and
tearing down — see
[Local Development: Kubernetes via Minikube](../development/local-development.md#-medium-kubernetes-via-minikube).
Unlike Docker Compose, the frontend runs **inside** the cluster here
(`infra/k8s/base/frontend.yaml`).

See [`../../infra/k8s/README.md`](../../infra/k8s/README.md) for the full manifest layout,
[`../cloud/azure/README.md`](../cloud/azure/README.md) for the AKS target this same
configuration deploys to in the cloud, and [`gitops.md`](gitops.md) for how Argo CD keeps the
cluster reconciled to these manifests.
