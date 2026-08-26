# ⚙️ Operations

How to run, deploy, and observe the platform: Docker Compose for local development,
Kubernetes/Kustomize and Helm for cluster deployment, the observability stack, endpoint
reference, and the CI/CD pipeline. For system design, see
[`../architecture/README.md`](../architecture/README.md). For reliability mechanisms, see
[`../reliability/README.md`](../reliability/README.md). For cloud deployment specifics, see
[`../cloud/azure/README.md`](../cloud/azure/README.md) (real) and
[`../cloud/aws/README.md`](../cloud/aws/README.md) (designed).

---

## Table of Contents

* [Observability](observability.md)
* [Containerization](../infrastructure/docker.md)
* [Kubernetes Architecture](../infrastructure/kubernetes.md)
* [Helm](../infrastructure/helm.md)
* [Development Endpoints](endpoints.md)
* [CI/CD Architecture](cicd.md)

---

## 📊 Observability

Metrics (Prometheus/Grafana), tracing (Zipkin), and health (Spring Actuator / Kubernetes
probes) — see [`observability.md`](observability.md).

## 🐳 Containerization

The complete platform runs locally via Docker Compose — see
[`../infrastructure/docker.md`](../infrastructure/docker.md).

## ☸️ Kubernetes Architecture

Kustomize-based manifests for Minikube and AKS — see
[`../infrastructure/kubernetes.md`](../infrastructure/kubernetes.md).

## ⚓ Helm

An umbrella Helm chart as the path toward a single-package cluster install — see
[`../infrastructure/helm.md`](../infrastructure/helm.md).

## 🌐 Development Endpoints

Every service's local URL — see [`endpoints.md`](endpoints.md).

## 🚀 CI/CD Architecture

Compile → test → build → deploy, and how GitOps fits in — see [`cicd.md`](cicd.md).
