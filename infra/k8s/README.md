# ☸️ Kubernetes Deployment

> **Kustomize-based Kubernetes deployments for local Minikube and Azure AKS environments.**

The platform uses a layered Kubernetes configuration:

```text
infra/k8s/
├── base/           # Shared production-shaped application manifests
├── overlays/
│   ├── dev/        # Self-contained local Minikube environment
│   └── aks/        # Azure AKS deployment environment
└── scripts/
    └── deploy-to-minikube.sh
```

The core principle is:

> **`base/` defines how the application runs. Overlays define where and under what infrastructure assumptions it runs.**

The same application topology is reused across environments while environment-specific infrastructure, images, secrets, resource sizing, and runtime configuration are applied through Kustomize overlays.

---

## Table of Contents

* [Deployment Model](#-deployment-model)
* [Repository Layout](#-repository-layout)
* [Base: Shared Application Manifests](#-base-shared-application-manifests)
* [Development Overlay: Minikube](#-development-overlay-minikube)
* [AKS Overlay: Azure Deployment](#-aks-overlay-azure-deployment)
* [Environment Comparison](#-environment-comparison)
* [Deviations from Base](#-deviations-from-base)
* [Running on Minikube](#-running-on-minikube)
* [GitOps and Argo CD](#-gitops-and-argo-cd)
* [Startup and Dependency Behavior](#-startup-and-dependency-behavior)
* [Production Evolution](#-production-evolution)

---

# 🏗️ Deployment Model

The platform is built around a shared Kustomize base:

```text
                         Shared Application Model

                              infra/k8s/base
                                    │
                 ┌──────────────────┴──────────────────┐
                 │                                     │
                 ▼                                     ▼
       infra/k8s/overlays/dev               infra/k8s/overlays/aks
                 │                                     │
                 ▼                                     ▼
          Local Minikube                          Azure AKS
          Self-contained                      CI/CD deployed
          Local images                         Images from ACR
          Local datastores                    Self-hosted datastores
```

The base describes the common platform.

Each overlay changes only what is required for that environment.

This prevents maintaining separate copies of every Deployment, Service, ConfigMap, and routing rule.

---

# 📁 Repository Layout

```text
infra/k8s/
│
├── base/
│   │
│   ├── namespace.yaml
│   ├── configmap.yaml
│   ├── secrets.yaml
│   ├── ingress.yaml
│   │
│   ├── frontend.yaml
│   ├── identity-service.yaml
│   ├── patient-service.yaml
│   ├── doctor-service.yaml
│   ├── appointment-service.yaml
│   ├── emr-service.yaml
│   ├── billing-service.yaml
│   ├── notification-service.yaml
│   ├── audit-service.yaml
│   ├── analytics-service.yaml
│   │
│   └── kustomization.yaml
│
├── overlays/
│   │
│   ├── dev/
│   │   ├── datastores/
│   │   ├── patches/
│   │   ├── platform-secrets
│   │   └── kustomization.yaml
│   │
│   └── aks/
│       ├── datastores/
│       ├── patches/
│       ├── images/
│       └── kustomization.yaml
│
└── scripts/
    └── deploy-to-minikube.sh
```

At a high level:

| Layer           | Responsibility                                             |
| --------------- | ---------------------------------------------------------- |
| `base/`         | Shared application topology and production-shaped defaults |
| `overlays/dev/` | Self-contained local Kubernetes environment                |
| `overlays/aks/` | AKS-specific image and deployment configuration            |
| `scripts/`      | Environment bootstrap and local deployment automation      |

---

# 🧱 Base: Shared Application Manifests

`base/` contains the Kubernetes representation of the application itself.

It includes:

* the `healthcare-platform` namespace
* the shared application ConfigMap
* the base Secret definition
* the frontend
* all nine Spring Boot services
* Kubernetes Services
* the platform Ingress

The base does **not** include the underlying datastore infrastructure.

```text
                    ┌─────────────────────────┐
                    │        Ingress          │
                    │   Single Entry Point    │
                    └────────────┬────────────┘
                                 │
              ┌──────────────────┼───────────────────────┐
              │                  │                       │
              ▼                  ▼                       ▼
        REST Services      GraphQL Gateway           Frontend
              │                  │                       │
              └──────────────────┴───────────────────────┘
                                 │
                                 ▼
                         Domain Services
                                 │
          ┌──────────┬───────────┼──────────┬───────────┐
          ▼          ▼           ▼          ▼           ▼
       Postgres    Redis       Kafka     RabbitMQ      MinIO
```

The base assumes these infrastructure dependencies are reachable through Kubernetes Service DNS names.

For example:

```text
postgres.healthcare-platform.svc.cluster.local
redis.healthcare-platform.svc.cluster.local
kafka.healthcare-platform.svc.cluster.local
rabbitmq.healthcare-platform.svc.cluster.local
minio.healthcare-platform.svc.cluster.local
```

This gives the application a stable infrastructure contract regardless of where it is deployed.

---

## 🌐 Single External Entry Point

The platform exposes a single Kubernetes `Ingress`.

See:

[`docs/adr/0001-api-gateway-ingress.md`](../../docs/adr/0001-api-gateway-ingress.md)

The routing model is:

```text
                                  Internet / Client
                                         │
                                         ▼
                                  Kubernetes Ingress
                                         │
          ┌──────────────────────┬─────────┴─────────┬──────────────────────┐
          │                      │                   │                      │
          ▼                      ▼                   ▼                      ▼
   /api/v1/auth          /api/v1/patients      /graphql              Everything else
          │                      │                   │                      │
          ▼                      ▼                   ▼                      ▼
  identity-service       patient-service       graphql-gateway          frontend


   /api/v1/doctors                /api/v1/appointments
          │                                │
          ▼                                ▼
   doctor-service                 appointment-service
```

The GraphQL Gateway acts as the platform's GraphQL BFF.

```text
Client
   │
   ▼
/graphql
   │
   ▼
graphql-gateway
   │
   ├──► Domain service A
   ├──► Domain service B
   ├──► Domain service C
   └──► Aggregated response
```

The frontend receives all remaining routes.

---

## ☁️ Infrastructure assumptions

The base intentionally defines **application connectivity**, not infrastructure provisioning.

The application receives infrastructure locations through environment variables such as:

```text
DB_HOST
KAFKA_BOOTSTRAP_SERVERS
REDIS_HOST
RABBITMQ_HOST
MINIO_ENDPOINT
```

The environment decides what those values represent.

```text
                 Application Environment Variables

                              DB_HOST
                                │
              ┌─────────────────┴─────────────────┐
              │                                   │
              ▼                                   ▼
          Minikube                         Cloud Environment
              │                                   │
              ▼                                   ▼
      postgres Service                    Managed PostgreSQL
      inside cluster                      or private endpoint
```

A production environment can replace in-cluster infrastructure with managed services without requiring application manifest duplication.

Typical managed equivalents include:

| Application dependency | Possible managed equivalent         |
| ---------------------- | ----------------------------------- |
| PostgreSQL             | RDS / Azure Database for PostgreSQL |
| Kafka                  | MSK / Confluent Cloud               |
| Redis                  | ElastiCache / Azure Managed Redis   |
| RabbitMQ               | CloudAMQP or managed broker         |
| MinIO                  | S3 / Azure Blob Storage             |

The repository's base manifests do not provision these services. The configuration comments in `base/configmap.yaml` describe the expected override points.

---

## 🚧 What the base does not install

The base contains an `Ingress` resource, but it does **not** install an Ingress controller.

These are different responsibilities:

```text
Kubernetes Ingress Resource
          │
          │ describes routing rules
          ▼
Ingress Controller
          │
          │ implements those rules
          ▼
Actual HTTP traffic routing
```

A cluster must already have an appropriate controller installed, such as an NGINX Ingress Controller.

See [ADR-0001](../../docs/adr/0001-api-gateway-ingress.md) for the ownership and architectural reasoning.

---

# 🟢 Development Overlay: Minikube

`overlays/dev/` creates a self-contained Kubernetes environment for local development.

On top of the shared application base, it adds the infrastructure needed to run the platform locally.

```text
Minikube Cluster
│
├── Application
│   ├── frontend
│   ├── identity-service
│   ├── patient-service
│   ├── doctor-service
│   ├── appointment-service
│   ├── emr-service
│   ├── billing-service
│   ├── notification-service
│   ├── audit-service
│   ├── analytics-service
│   └── graphql-gateway
│
└── Local Infrastructure
    ├── PostgreSQL
    ├── Redis
    ├── ZooKeeper
    ├── Kafka
    ├── RabbitMQ
    └── MinIO
```

The datastore Deployments are deliberately named to match the infrastructure DNS names expected by the application base.

For example:

```text
Deployment/Service name: postgres
                │
                ▼
postgres.healthcare-platform.svc.cluster.local
```

This allows the development overlay to remain compatible with the application's base configuration.

---

## Included datastores

The development environment includes:

* PostgreSQL
* Redis
* ZooKeeper
* Kafka
* RabbitMQ
* MinIO

Each runs as a simple single-replica workload suitable for local development.

The following components are intentionally not part of the Minikube overlay:

* Elasticsearch
* Zipkin
* Prometheus
* Grafana

The application currently does not require these components to start and operate the core platform.

---

## Development-specific configuration

The development overlay adds:

```text
overlays/dev/
├── datastores/
├── patches/
├── platform-secrets
├── replica overrides
└── resource overrides
```

The overlay provides real development credentials for:

* application secrets
* database users
* datastore credentials

These values allow the local cluster to be self-contained.

---

# 🔵 AKS Overlay: Azure Deployment

`overlays/aks/` deploys the platform to the Azure Kubernetes Service environment provisioned by:

```text
infra/terraform/envs/dev/
```

Unlike the Minikube workflow, the AKS overlay is deployed by CI/CD:

```text
GitHub Actions
       │
       ▼
.github/workflows/ci.yml
       │
       ▼
Kustomize
       │
       ▼
Azure AKS
```

The overlay reuses the shared application base while providing AKS-specific image handling and environment configuration.

---

## 🐳 Images from Azure Container Registry

The AKS overlay overrides service images to point to the Azure Container Registry login server.

The committed Kustomize configuration uses placeholders:

```text
PLACEHOLDER_ACR
PLACEHOLDER_TAG
```

During deployment, the workflow substitutes the real image references using:

```text
kustomize edit set image
```

The resulting deployment flow is:

```text
Source Change
     │
     ▼
GitHub Actions
     │
     ├── Build image
     ├── Tag image
     ├── Push image to ACR
     │
     ▼
Kustomize image substitution
     │
     ▼
kubectl apply -k
     │
     ▼
Azure AKS
```

This allows the committed manifests to remain Kustomize-buildable without storing environment-specific registry values directly in the repository.

---

## 🗄️ AKS datastore model

The AKS overlay currently deploys self-hosted instances of:

* PostgreSQL
* Redis
* ZooKeeper
* Kafka
* RabbitMQ
* MinIO

The `payer-mock` component is not included because the AKS overlay does not require it.

The Terraform environment already provisions managed infrastructure, including services intended for PostgreSQL, event streaming, and caching.

Those managed services are not currently wired into the Kubernetes application overlay.

This is intentional because they are not drop-in replacements for the current application configuration.

For example, the existing Kafka application configuration assumes per-service SASL/SCRAM-style connectivity, while Azure Event Hubs uses a different authentication and connection model.

```text
Current Application Model
        │
        ▼
Kafka Broker
├── Service-specific users
├── SASL/SCRAM
└── Kafka broker configuration


Azure Event Hubs Model
        │
        ▼
Event Hubs Namespace
└── Shared connection and authentication model
```

Connecting the application to managed Azure infrastructure therefore requires a deliberate infrastructure integration rather than simply replacing a hostname.

---

# 🔐 AKS Secrets

The AKS environment does not commit real platform secrets to Git.

Instead, the deployment workflow creates the runtime secret from GitHub Actions secrets.

Conceptually:

```text
GitHub Actions Secrets
         │
         ▼
kubectl create secret ...
         │
         ▼
kubectl apply -f -
         │
         ▼
platform-secrets
         │
         ▼
AKS workloads
```

This happens after the Kustomize deployment so the real runtime secret replaces the placeholder secret included in the base manifests.

The current implementation deliberately does not use a Kustomize `secretGenerator` for these values.

---

# 📊 Environment Comparison

| Concern              | `base/`                  | `overlays/dev/`        | `overlays/aks/`           |
| -------------------- | ------------------------ | ---------------------- | ------------------------- |
| Application services | Defines all workloads    | Reuses base            | Reuses base               |
| Frontend             | Defined                  | Runs in cluster        | Runs in cluster           |
| Datastores           | Not included             | Self-hosted            | Self-hosted               |
| PostgreSQL           | External assumption      | In-cluster             | In-cluster                |
| Kafka                | External assumption      | In-cluster             | In-cluster                |
| Image source         | Generic image references | Minikube local images  | Azure Container Registry  |
| Image tag            | Base/default             | Local development tags | CI-provided immutable tag |
| Resource sizing      | Production-shaped        | Reduced for local VM   | Base sizing retained      |
| Replicas             | Production-shaped        | 1 per workload         | 1 per workload            |
| Kafka security       | Production-shaped        | PLAINTEXT              | PLAINTEXT                 |
| Quartz job store     | JDBC-oriented            | Memory                 | Memory                    |
| Secrets              | Base placeholder         | Development values     | GitHub Actions secrets    |
| Deployment           | N/A                      | Local script / Argo CD | GitHub Actions            |

---

# ⚙️ Deviations from Base

Some environment-specific behavior exists because a local or constrained environment does not provide the same infrastructure guarantees as the shared base.

---

## 🔓 Kafka uses PLAINTEXT

The Docker Compose environment uses Kafka with SASL/TLS and generated development certificates.

Replicating the full setup inside a disposable local Kubernetes cluster would require additional certificate and authentication infrastructure.

The development and AKS self-hosted Kafka environments therefore apply:

```text
spring.kafka.properties.security.protocol=PLAINTEXT
```

through:

```text
patches/kafka-plaintext.yaml
```

The same patch is reused by the AKS overlay because its Kafka broker is also self-hosted without the Docker Compose TLS/JAAS setup.

```text
Production-shaped configuration
            │
            ▼
       SASL / TLS


Local self-hosted Kafka
            │
            ▼
        PLAINTEXT
```

This is an environment-specific simplification, not the intended security posture for a managed production Kafka deployment.

---

## 🕐 Quartz uses an in-memory job store

The Analytics Service uses Quartz for durable scheduled reporting.

A JDBC-backed Quartz store requires Quartz's standard `QRTZ_*` tables.

The repository does not currently provision those tables for the self-hosted Kubernetes PostgreSQL environment.

Without the tables:

```text
Analytics Service
       │
       ▼
SchedulerFactoryBean
       │
       ▼
Quartz JDBC store
       │
       ▼
Missing QRTZ_* tables
       │
       ▼
Application startup failure
       │
       ▼
CrashLoopBackOff
```

The development and AKS overlays therefore apply:

```text
patches/quartz-memory.yaml
```

This changes Quartz to an in-memory job store.

The trade-off is explicit:

```text
JDBC Store
├── Persistent jobs
├── Restart-aware
└── Requires QRTZ_* schema


Memory Store
├── No schema required
├── Easy local startup
└── Job state lost on pod restart
```

The in-memory configuration is appropriate for the current non-production environments but does not provide the durable scheduling characteristics intended for a production reporting workload.

---

## 🐳 Local image pull policy

The Minikube environment uses locally built images.

When images are tagged as:

```text
:latest
```

Kubernetes may otherwise attempt to retrieve them from a registry instead of using the locally available image.

The development overlay therefore applies:

```text
imagePullPolicy: IfNotPresent
```

This allows the kubelet to use images already loaded into Minikube.

The AKS overlay does not reuse this patch because CI deploys immutable image tags.

With immutable Git SHA-based image versions, the default `IfNotPresent` behavior is appropriate.

---

## 📉 Reduced local resources

The base configuration is sized for a real cluster.

The development overlay reduces resource requirements and pins workloads to a single replica so the complete stack can run inside a local Minikube VM.

```text
Base
├── Production-shaped requests
└── Production-shaped replicas


Development
├── 1 replica per workload
└── Reduced CPU and memory requests
```

The development target is approximately:

```text
~3 CPU
~5.5 GiB memory
```

for the complete application and local datastore stack.

The AKS overlay also pins workloads to one replica, but for a different reason: the current AKS proof-of-concept cluster is constrained by a 4-vCPU quota and cannot accommodate the full production-shaped replica count.

The AKS overlay keeps the base resource sizing and probe behavior rather than reusing the Minikube-specific resource and probe patches.

---

# 🚀 Running on Minikube

## Check available resources first

The full local environment includes:

```text
10 application workloads
    +
6 datastore workloads
    =
16 workloads
```

Even with development sizing, the stack requires approximately:

```text
CPU:     ~3 cores
Memory:  ~5.5 GiB
```

When using the Docker driver, Minikube runs within the resources available to Docker Desktop.

The effective hierarchy is:

```text
Your Machine
    │
    ▼
Docker Desktop Resource Allocation
    │
    ▼
Minikube Docker Driver
    │
    ▼
Kubernetes Pods
```

Before starting Minikube, ensure Docker Desktop has sufficient CPU and memory allocated.

Also account for unrelated containers already running on Docker Desktop.

---

## Start the cluster

```bash id="fny7x9"
minikube start --cpus=4 --memory=7168 --driver=docker
```

Deploy the platform:

```bash id="g5zcj1"
./infra/k8s/scripts/deploy-to-minikube.sh
```

The deployment script:

1. Enables the Minikube ingress addon.
2. Builds the nine backend service images.
3. Builds the frontend image.
4. Loads the images into Minikube.
5. Applies `overlays/dev`.
6. Waits for workloads to become available.

The first build can take time because each service requires its container build process.

Subsequent runs benefit from Docker layer caching.

---

# 🌐 Access the Platform

All application traffic is exposed through the Ingress.

Port-forward the ingress controller:

```bash id="xtjix3"
kubectl -n ingress-nginx port-forward \
  svc/ingress-nginx-controller 8080:80
```

Open:

```text id="uj5x2x"
http://localhost:8080/
```

The ingress handles routing to the appropriate backend service, GraphQL Gateway, or frontend.

---

# 🔍 Useful Kubernetes Commands

Watch pod startup:

```bash id="v9yw47"
kubectl -n healthcare-platform get pods -w
```

View a service's logs:

```bash id="dlrzq1"
kubectl -n healthcare-platform logs deploy/appointment-service
```

Inspect all resources:

```bash id="qyr1u2"
kubectl -n healthcare-platform get all
```

Restart a deployment:

```bash id="0dqzxg"
kubectl -n healthcare-platform rollout restart \
  deployment/<service>
```

Watch rollout progress:

```bash id="l46f9d"
kubectl -n healthcare-platform rollout status \
  deployment/<service>
```

---

# 🔨 Rebuilding After a Code Change

The simplest workflow is to run the deployment script again:

```bash id="ljvekn"
./infra/k8s/scripts/deploy-to-minikube.sh
```

Docker's build cache makes unchanged services significantly faster on subsequent runs.

For a targeted change, rebuild and reload only the affected service:

```bash id="ly2o2s"
docker build \
  -t healthcare-platform/<service>:latest \
  -f backend/<service>/Dockerfile \
  backend

minikube image load healthcare-platform/<service>:latest

kubectl -n healthcare-platform rollout restart \
  deployment/<service>
```

This avoids rebuilding the entire platform when working on one service.

---

# 🔄 GitOps and Argo CD

The development overlay can also be reconciled through Argo CD.

```text
infra/argocd/
      │
      ▼
Argo CD Application
      │
      ▼
infra/k8s/overlays/dev
      │
      ▼
Minikube Cluster
```

See:

* [`infra/argocd/README.md`](../argocd/README.md)
* [ADR-0005 — GitOps with Argo CD](../../docs/adr/0005-gitops-with-argocd.md)

Argo CD replaces the manual manifest reconciliation loop:

```text
Without GitOps:

Developer
    │
    ▼
kubectl apply -k
    │
    ▼
Cluster


With GitOps:

Developer
    │
    ▼
git push
    │
    ▼
Argo CD
    │
    ▼
Cluster
```

The image build problem remains separate.

Images must still exist in Minikube before Argo CD can deploy workloads that reference them.

For local development:

```text
Build image
    │
    ▼
Load into Minikube
    │
    ▼
GitOps reconciles manifests
```

See [`docs/infrastructure/gitops.md`](../../docs/infrastructure/gitops.md) for the full GitOps architecture and operational model.

---

# 🔵 Why AKS Is Not Managed by Argo CD

The AKS environment is intentionally deployed through:

```text
.github/workflows/ci.yml
```

rather than the Argo CD configuration under `infra/argocd/`.

This is deliberate.

The current CI/CD workflow performs responsibilities beyond manifest reconciliation:

```text
Build
  │
  ▼
Push image to ACR
  │
  ▼
Substitute image tag
  │
  ▼
kubectl apply -k
  │
  ▼
Apply runtime secrets
  │
  ▼
AKS
```

Adding an Argo CD Application that also manages the same overlay would create two competing deployment controllers.

With self-healing enabled:

```text
GitHub Actions ──► Changes cluster
                       │
                       ▼
                   Argo CD
                       │
                       ▼
              Reconciles back to Git
```

The two systems would eventually conflict if Git did not contain the same image references and secret-management model as the CI/CD deployment.

A production GitOps model for AKS would require a different delivery flow:

```text
CI
│
├── Build image
├── Test image
└── Push immutable image to ACR
          │
          ▼
Update Git-managed image reference
          │
          ▼
Commit desired state
          │
          ▼
Argo CD reconciles AKS
```

This requires a Git write-back or image-update mechanism rather than simply copying the development Argo CD Application.

---

# ⏳ Startup and Dependency Behavior

The application workloads do not currently use init containers to wait for every dependency before starting.

A cold cluster startup can therefore look like:

```text
Kubernetes starts PostgreSQL
          │
          │ still initializing
          │
          ├───────────────────────────────┐
          │                               │
          ▼                               ▼
Services start                     PostgreSQL not ready
          │                               │
          ▼                               ▼
Flyway / JPA connection         Application startup fails
          │                               │
          └──────────────┬────────────────┘
                         │
                         ▼
                   Pod restarts
                         │
                         ▼
              PostgreSQL becomes ready
                         │
                         ▼
                Next application start
                         │
                         ▼
                    Service becomes Ready
```

A few restart cycles during a cold startup are therefore expected.

The PostgreSQL initialization process also creates the service-specific databases and users.

`emr-service` has an additional startup dependency on MinIO because it creates its required bucket during initialization.

If MinIO is not ready:

```text
emr-service starts
       │
       ▼
MinIO unavailable
       │
       ▼
Startup failure
       │
       ▼
Kubernetes restart
       │
       ▼
MinIO becomes ready
       │
       ▼
Successful startup
```

The current Kubernetes restart behavior allows the platform to recover automatically once dependencies become available.

---

# 🔮 Production Evolution

The current Kubernetes architecture establishes a shared deployment model while keeping local and cloud environments explicit.

A more mature production topology would move toward:

```text
                              Kubernetes
                                  │
                  ┌───────────────┴────────────────┐
                  │                                │
                  ▼                                ▼
            Application Pods                 Managed Services
                  │                                │
                  ├── Spring Boot Services         ├── Managed PostgreSQL
                  ├── GraphQL Gateway              ├── Managed Kafka
                  └── Frontend                     ├── Managed Redis
                                                   ├── Object Storage
                                                   └── Managed Messaging
```

The corresponding delivery pipeline would evolve toward:

```text
Source Code
    │
    ▼
CI Pipeline
├── Test
├── Build
├── Scan
└── Push Immutable Image
          │
          ▼
Container Registry
          │
          ▼
Git-managed Deployment State
          │
          ▼
Argo CD
          │
          ▼
Kubernetes Environment
```

The current implementation already establishes the key boundaries needed for that evolution:

* shared application manifests
* environment-specific overlays
* Kustomize-based configuration
* registry-based image overrides
* CI/CD deployment to AKS
* GitOps reconciliation for the local Kubernetes environment
* explicit separation between application configuration and infrastructure provisioning
* a clear path from self-hosted development dependencies to managed cloud services

---

## 📚 Related Documentation

* [`docs/architecture/README.md`](../../docs/architecture/README.md) — platform architecture and service communication
* [`docs/infrastructure/gitops.md`](../../docs/infrastructure/gitops.md) — Argo CD and GitOps deployment model
* [`docs/operations/README.md`](../../docs/operations/README.md) — deployment and operational workflows
* [ADR-0001 — API Gateway and Ingress](../../docs/adr/0001-api-gateway-ingress.md)
* [ADR-0005 — GitOps with Argo CD](../../docs/adr/0005-gitops-with-argocd.md)
* [`../terraform/envs/dev/`](../terraform/envs/dev/) — Azure infrastructure provisioning
