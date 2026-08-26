# ⚙️ Operations

How to run, deploy, and observe the platform: Docker Compose for local development,
Kubernetes/Kustomize and Helm for cluster deployment, the observability stack, endpoint
reference, and the CI/CD pipeline. For system design, see [`ARCHITECTURE.md`](ARCHITECTURE.md).
For reliability mechanisms, see [`RELIABILITY.md`](RELIABILITY.md). For cloud deployment
specifics, see [`AZURE_ARCHITECTURE.md`](AZURE_ARCHITECTURE.md) (real) and
[`AWS_ARCHITECTURE.md`](AWS_ARCHITECTURE.md) (designed).

---

## Table of Contents

* [Observability](#-observability)
* [Containerization](#-containerization)
* [Kubernetes Architecture](#-kubernetes-architecture)
* [Helm](#-helm)
* [Development Endpoints](#-development-endpoints)
* [CI/CD Architecture](#-cicd-architecture)

---

## 📊 Observability

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
[`PROGRESS.md`](PROGRESS.md#designed-not-yet-built).

---

## 🐳 Containerization

The complete platform can be launched locally with Docker Compose.

The development environment includes PostgreSQL, Redis, Kafka, Zookeeper, RabbitMQ,
Elasticsearch, MinIO, Prometheus, Grafana, and Zipkin, alongside all nine Spring Boot
domain services plus `graphql-gateway`. Application containers include health checks and
resource limits to better approximate production deployment behavior. The frontend is not
part of `docker-compose.yml` — it always runs on the host (`npm start`).

For the exact commands (and two other ways to run the platform locally), see
[`GETTING_STARTED.md`](GETTING_STARTED.md#-easy-full-docker-compose).

---

## ☸️ Kubernetes Architecture

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

### Kubernetes with Minikube

For the exact commands — starting Minikube, deploying, rebuilding a service's image, and
tearing down — see [`GETTING_STARTED.md`](GETTING_STARTED.md#-medium-kubernetes-via-minikube).
Unlike Docker Compose, the frontend runs **inside** the cluster here
(`infra/k8s/base/frontend.yaml`).

---

## ⚓ Helm

The project includes an umbrella Helm chart at `infra/helm/healthcare-platform/`. Rather than
hand-writing nine near-identical charts, `values.yaml` holds one entry per service (port, DB
name/user, replica count) that drives a single parameterized `templates/service.yaml`
(Deployment + Service pair). Day-to-day local/dev deployment uses the Kustomize manifests
under `infra/k8s/`; the Helm chart is the path toward a single-package cluster install.

---

## 🌐 Development Endpoints

| Component            | URL                      |
| --------------------- | ------------------------ |
| Identity Service      | `http://localhost:8081`  |
| Patient Service       | `http://localhost:8082`  |
| Doctor Service        | `http://localhost:8083`  |
| Appointment Service   | `http://localhost:8084`  |
| EMR Service           | `http://localhost:8085`  |
| Billing Service       | `http://localhost:8086`  |
| Notification Service  | `http://localhost:8087`  |
| Audit Service         | `http://localhost:8088`  |
| Analytics Service     | `http://localhost:8089`  |
| GraphQL Gateway       | `http://localhost:8090`  |
| RabbitMQ Management   | `http://localhost:15672` |
| Elasticsearch         | `http://localhost:9200`  |
| MinIO Console         | `http://localhost:9001`  |
| Prometheus            | `http://localhost:9090`  |
| Grafana               | `http://localhost:3000`  |
| Zipkin                | `http://localhost:9411`  |

API documentation is available on each application service at `/docs.html`.

---

## 🚀 CI/CD Architecture

```text
Commit ──► Compile ──► Unit Tests ──► Integration Tests (Testcontainers)
        ──► Static Analysis ──► Docker Build ──► Container Registry ──► Deployment
```

Integration tests use Testcontainers against real infrastructure dependencies instead of
relying exclusively on mocks. The workflow is at `.github/workflows/ci.yml`.

Deployment itself is GitOps-driven via ArgoCD, not a manual `kubectl apply` — see
[`GITOPS.md`](GITOPS.md) for how the reconcile loop works and its known limitations.
