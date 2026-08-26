# 🚀 Getting Started

The platform supports four local development workflows, ranging from a single-command Docker environment to a fully native setup with no containers.

Choose the workflow based on what you want to do—you only need to follow one.

| Workflow                                     | Setup           | Frontend               | Best for                                                    |
| -------------------------------------------- | --------------- | ---------------------- | ----------------------------------------------------------- |
| 🟢 **Easy — Full Docker Compose**            | Minimal         | Runs on your host      | Exploring the platform and running the full system quickly  |
| 🟡 **Medium — Kubernetes via Minikube**      | Moderate        | Runs inside Kubernetes | Testing Kubernetes manifests, Helm, and deployment behavior |
| 🔴 **Advanced — Hybrid Development Loop**    | Manual          | Runs on your host      | Developing and debugging individual services                |
| ⚫ **Fully Native — No Docker or Kubernetes** | Automated setup | Runs on your host      | Running the platform entirely as local OS processes         |

## Prerequisites

| Workflow         | Required                                        |
| ---------------- | ----------------------------------------------- |
| **Easy**         | Docker + Docker Compose                         |
| **Medium**       | Docker, Docker Compose, Minikube, kubectl       |
| **Advanced**     | Docker, Docker Compose, Java 21, Maven, Node.js |
| **Fully Native** | macOS, Homebrew, Java 21, Maven, Node.js        |

For a detailed view of the platform architecture and service responsibilities, see [`ARCHITECTURE.md`](ARCHITECTURE.md). For operational details, endpoints, Helm, and CI/CD workflows, see [`OPERATIONS.md`](OPERATIONS.md).

---

## 🟢 Easy: Full Docker Compose

The fastest way to run the complete backend platform locally.

Docker Compose provisions the application's supporting infrastructure and services, including:

* PostgreSQL
* Redis
* Kafka
* RabbitMQ
* Elasticsearch
* MinIO
* Payer eligibility mock service
* Zipkin
* Prometheus
* Grafana
* Nine domain microservices
* GraphQL Gateway

### Start the platform

Generate the local Kafka development certificates:

```bash
./infra/docker/kafka/generate-dev-certs.sh
```

Start the complete platform:

```bash
docker compose up --build
```

Or run it in the background:

```bash
docker compose up --build -d
docker compose ps
```

### Run the frontend

The frontend runs independently on the host machine, providing a fast frontend development loop:

```bash
cd frontend
npm install
npm start
```

Open:

```text
http://localhost:4200
```

The frontend uses its development proxy configuration to route requests to the backend services.

### Useful commands

```bash
# Follow logs from the entire platform
docker compose logs -f

# Follow logs from one service
docker compose logs -f appointment-service

# Inspect running containers
docker compose ps

# Stop and remove the environment
docker compose down
```

### Development characteristics

This workflow provides the quickest path to a complete, integrated environment. It is ideal for API exploration, end-to-end testing, and understanding how the services interact.

For active backend development, use the Hybrid Development Loop when you need direct IDE debugging and a tighter code-change cycle.

---

## 🟡 Medium: Kubernetes via Minikube

This workflow runs the platform using the same Kubernetes-oriented deployment model used by the project's cloud environments.

Instead of Docker Compose services, the platform is deployed through Kubernetes resources such as:

* Deployments
* Services
* ConfigMaps
* Secrets
* Ingress resources
* Environment-specific Kustomize overlays

The frontend also runs inside the cluster.

### Start Minikube

```bash
minikube start --cpus=4 --memory=7168 --driver=docker
```

Deploy the development environment:

```bash
./infra/k8s/scripts/deploy-to-minikube.sh
```

Watch the workload status:

```bash
kubectl -n healthcare-platform get pods -w
```

Wait until the required workloads reach the expected ready state.

### Access the platform

Expose the Kubernetes ingress locally:

```bash
kubectl -n ingress-nginx port-forward \
  svc/ingress-nginx-controller 8080:80
```

Open:

```text
http://localhost:8080/
```

Example routes include:

```text
/api/v1/auth
/api/v1/patients
/api/v1/doctors
/api/v1/appointments
/graphiql
```

### Rebuild a service after code changes

When changing backend code, rebuild the service image, load it into Minikube, and restart the workload:

```bash
docker build \
  -t healthcare-platform/<service>:latest \
  -f backend/<service>/Dockerfile \
  backend

minikube image load healthcare-platform/<service>:latest

kubectl -n healthcare-platform rollout restart \
  deployment/<service>
```

Monitor the rollout:

```bash
kubectl -n healthcare-platform rollout status \
  deployment/<service>
```

### Debug a service directly

Port-forward directly to an individual service when debugging:

```bash
kubectl -n healthcare-platform port-forward \
  svc/identity-service 8081:8081
```

### Tear down

Remove the development overlay:

```bash
kubectl delete -k infra/k8s/overlays/dev
```

Stop Minikube:

```bash
minikube stop
```

### Development characteristics

This workflow is the best choice when validating Kubernetes configuration, deployment behavior, service discovery, ingress routing, resource configuration, or Helm/Kustomize changes.

It intentionally provides a more production-oriented environment at the cost of a slower edit-build-deploy loop.

---

## 🔴 Advanced: Hybrid Development Loop

The Hybrid Development Loop is designed for active backend development.

Supporting infrastructure continues to run in containers, while the service you are actively developing runs directly from your machine through your IDE or Maven. This provides a more efficient development workflow with direct debugger access and rapid application restarts.

### 1. Start the infrastructure

Generate the Kafka development certificates:

```bash
./infra/docker/kafka/generate-dev-certs.sh
```

Start only the infrastructure and supporting observability services:

```bash
docker compose up -d \
  postgres \
  redis \
  zookeeper \
  kafka \
  rabbitmq \
  elasticsearch \
  minio \
  payer-mock \
  zipkin \
  prometheus \
  grafana
```

### 2. Build the backend modules

Build the multi-module project once:

```bash
cd backend
mvn clean install -DskipTests
```

This installs shared modules such as `common` and `grpc-contracts` into your local Maven repository so individual services can be run independently.

### 3. Run the service under development

From the command line:

```bash
cd backend/appointment-service
mvn spring-boot:run
```

For the best debugging experience, run the service directly from your IDE and attach breakpoints as usual.

### 4. Run additional services in Docker

Services that are not actively being modified can continue running in containers:

```bash
docker compose up -d \
  patient-service \
  doctor-service \
  graphql-gateway
```

This allows the environment to mix:

```text
┌──────────────────────────────────────────────┐
│                 Development Host             │
│                                              │
│  IDE                                         │
│   │                                          │
│   └── appointment-service ──────────────┐    │
│                                         │    │
└─────────────────────────────────────────┼────┘
                                          │
                                          ▼
┌──────────────────────────────────────────────┐
│              Docker Compose Network           │
│                                              │
│  PostgreSQL   Redis   Kafka   RabbitMQ       │
│  Elasticsearch   MinIO   Zipkin              │
│                                              │
│  patient-service   doctor-service            │
│  graphql-gateway                             │
└──────────────────────────────────────────────┘
```

The native service connects to the locally published infrastructure ports, while containerized services continue communicating through the Docker environment.

### 5. Run the frontend

```bash
cd frontend
npm install
npm start
```

### Authentication configuration

Services use a shared development JWT configuration unless explicitly overridden.

If you configure `JWT_SECRET`, use the same value for every service that issues or validates tokens:

```bash
export JWT_SECRET="your-shared-development-secret"
```

Inconsistent JWT secrets between the identity provider and services that validate tokens will cause authentication failures.

### Tear down

Stop Docker infrastructure:

```bash
docker compose down
```

Stop locally running services using:

```text
Ctrl+C
```

or your IDE's process controls.

### Development characteristics

This is the recommended workflow for implementing features, investigating bugs, profiling a service, and debugging distributed interactions without rebuilding the entire platform for every code change.

---

## ⚫ Fully Native: No Docker, No Kubernetes

The native workflow runs the platform directly on macOS without Docker, Docker Compose, Kubernetes, Minikube, or container runtimes.

The environment is managed by:

```bash
./infra/native/run.sh
```

### Start the platform

```bash
./infra/native/run.sh
```

The native runner prepares and starts the local development environment, including:

1. Validating Java 21 availability.
2. Preparing required Homebrew-managed infrastructure.
3. Starting PostgreSQL, Redis, and RabbitMQ as local services.
4. Creating the required per-service databases and roles.
5. Preparing Kafka in KRaft mode.
6. Building the multi-module backend.
7. Starting the domain services.
8. Starting the GraphQL Gateway.
9. Starting the frontend.
10. Writing process logs under `.native-run/logs/`.

The workflow is designed to manage this project's environment without interfering with unrelated databases or application processes.

### Optional components

Start optional infrastructure such as Elasticsearch and MinIO:

```bash
./infra/native/run.sh --full
```

MinIO is used by the document-related functionality provided by `emr-service`.

Run without Kafka:

```bash
./infra/native/run.sh --skip-kafka
```

Services can still start, although functionality that depends on Kafka will not be available until Kafka is running.

### Access the platform

```
http://localhost:4200
```

`ng serve`'s own dev-server proxy (`frontend/proxy.conf.json`) fronts the frontend and
forwards `/api/v1/{auth,patients,doctors,appointments,invoices,billing}` and
`/graphql`/`/graphiql` to their owning service — one same-origin endpoint for the whole
platform, mirroring the routing table the Kubernetes Ingress uses
(`infra/k8s/base/ingress.yaml`, see [ADR-0001](docs/adr/0001-api-gateway-ingress.md)).
`emr-service`, `notification-service`, `audit-service`, and `analytics-service` aren't
proxied — same as the Ingress, they're event-driven/internal services with no UI consumer
today — reach them directly on their own port (8085/8087/8088/8089) if needed.

### Kafka configuration

The containerized and cloud environments use authenticated Kafka communication.

The native development workflow adapts the Spring configuration for a plain local Kafka broker by exporting environment-level configuration overrides, allowing the same application source to run against the native development broker without modifying `application.yml`.

Application configuration remains environment-driven, so deployment-specific transport and authentication settings can be supplied without changing service code.

### Stop the platform

Stop only processes started for this project:

```bash
./infra/native/stop.sh
```

To also stop the shared Homebrew services:

```bash
./infra/native/stop.sh --all
```

The default stop command intentionally leaves shared services running because PostgreSQL, Redis, RabbitMQ, and optional services may also be used by other local projects.

### Development characteristics

This workflow is useful when containers are unavailable, undesirable, or when you want to inspect and operate every dependency directly as an operating-system-level process.

---

## 🧭 Which workflow should I choose?

| Your goal                                        | Recommended workflow           |
| ------------------------------------------------ | ------------------------------ |
| Get the complete platform running quickly        | 🟢 **Easy — Docker Compose**   |
| Explore APIs and test end-to-end flows           | 🟢 **Easy — Docker Compose**   |
| Practice Kubernetes operations                   | 🟡 **Kubernetes via Minikube** |
| Test manifests, ingress, or deployment behavior  | 🟡 **Kubernetes via Minikube** |
| Develop and debug one backend service            | 🔴 **Hybrid Development Loop** |
| Use IDE breakpoints and direct service execution | 🔴 **Hybrid Development Loop** |
| Run without containers                           | ⚫ **Fully Native**             |
| Operate every dependency directly on the host OS | ⚫ **Fully Native**             |

> **Recommended starting point:** Use Docker Compose to explore the platform, move to the Hybrid Development Loop for active service development, and use Minikube when validating Kubernetes deployment behavior.

For a complete breakdown of service responsibilities and platform communication, see [`ARCHITECTURE.md`](ARCHITECTURE.md).

For operational commands, endpoint references, Helm deployment, observability, and CI/CD workflows, see [`OPERATIONS.md`](OPERATIONS.md).
