# 🔄 Argo CD — GitOps for Local Kubernetes

> **Git is the desired state. Argo CD continuously reconciles the Minikube cluster to match it.**

This directory contains the Argo CD configuration for the platform's local Kubernetes
environment.

It manages:

```text
infra/k8s/overlays/dev
        │
        ▼
Argo CD Application
        │
        ▼
healthcare-platform namespace
        │
        ▼
Minikube cluster
```

Instead of manually running `kubectl apply -k` whenever a Kubernetes manifest changes,
Argo CD watches the repository and reconciles the cluster to the Git-defined desired state.

For the architectural reasoning, see
[ADR-0005](../../docs/adr/0005-gitops-with-argocd.md).

For the complete GitOps architecture, reconciliation model, image delivery flow, and diagrams,
see [`docs/infrastructure/gitops.md`](../../docs/infrastructure/gitops.md).

---

## Table of Contents

- [What This Manages](#-what-this-manages)
- [Repository Layout](#-repository-layout)
- [How Reconciliation Works](#-how-reconciliation-works)
- [The Important Boundary: Images vs Manifests](#-the-important-boundary-images-vs-manifests)
- [Environment Scope](#-environment-scope)
- [Bootstrap on Minikube](#-bootstrap-on-minikube)
- [Accessing Argo CD](#-accessing-argo-cd)
- [Day-to-Day Workflow](#-day-to-day-workflow)
- [Manual Sync](#-manual-sync)
- [Troubleshooting](#-troubleshooting)
- [Related Documentation](#-related-documentation)

---

# 🎯 What This Manages

The GitOps configuration currently manages exactly one Kubernetes environment:

```text
Environment:     Development
Kustomize path:  infra/k8s/overlays/dev
Cluster target:  Minikube
Namespace:       healthcare-platform
```

The deployment relationship is:

```text
Git repository
      │
      │ desired state
      ▼
┌──────────────────────────────┐
│            Argo CD           │
│                              │
│  AppProject                  │
│       │                      │
│       ▼                      │
│  healthcare-platform-dev     │
│       │                      │
│       ▼                      │
│ infra/k8s/overlays/dev       │
└───────────────┬──────────────┘
                │
                │ reconcile
                ▼
      Kubernetes / Minikube
                │
                ▼
   healthcare-platform namespace
```

The goal is not to replace Kubernetes or Kustomize.

The responsibility split is:

| Component | Responsibility |
|---|---|
| Git | Stores the desired deployment state |
| Kustomize | Builds environment-specific Kubernetes manifests |
| Argo CD | Continuously reconciles Git state with cluster state |
| Kubernetes | Runs the workloads |
| `deploy-to-minikube.sh` | Builds and loads local container images |

---

# 📁 Repository Layout

```text
infra/argocd/
│
├── project.yaml
├── application-dev.yaml
└── README.md
```

## `project.yaml`

Defines the Argo CD `AppProject`.

Its purpose is to constrain this GitOps configuration to the platform's intended boundaries.

```text
AppProject: healthcare-platform
│
├── Allowed source
│   └── This repository
│
└── Allowed destination
    └── healthcare-platform namespace
```

The project is intentionally scoped rather than using Argo CD's default, broadly permissive
project.

This gives the deployment configuration an explicit boundary:

```text
Argo CD
   │
   └── healthcare-platform AppProject
           │
           ├── approved repository
           └── healthcare-platform namespace
```

---

## `application-dev.yaml`

Defines the development `Application`:

```text
Application: healthcare-platform-dev
│
├── Source
│   ├── Repository: this repository
│   ├── Revision: main
│   └── Path: infra/k8s/overlays/dev
│
├── Destination
│   └── Namespace: healthcare-platform
│
└── Sync Policy
    ├── Automated
    ├── prune: true
    └── selfHeal: true
```

The application points to the same Kustomize overlay used by:

```bash
./infra/k8s/scripts/deploy-to-minikube.sh
```

The difference is the reconciliation model.

```text
Imperative deployment

Developer
    │
    ▼
kubectl apply -k
    │
    ▼
Cluster


GitOps deployment

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

---

# 🔁 How Reconciliation Works

Argo CD continuously compares:

```text
Desired State                         Actual State

Git repository                        Kubernetes cluster
      │                                      │
      │                                      │
      └──────────────────┬───────────────────┘
                         ▼
                     Argo CD
                         │
                         ▼
                 Are they different?
                         │
                ┌────────┴────────┐
                │                 │
               No                Yes
                │                 │
                ▼                 ▼
             Healthy          Reconcile
                                  │
                                  ▼
                           Cluster matches Git
```

With automated sync enabled, a change merged or pushed to the tracked revision can be applied
without manually running `kubectl apply`.

---

## 🛡️ Self-healing

The application uses:

```yaml
selfHeal: true
```

This protects the cluster from manual configuration drift.

For example:

```text
Git
 │
 │ replicas: 1
 ▼
Cluster
 │
 ├── kubectl edit deployment
 │         │
 │         ▼
 │     replicas: 3
 │
 ▼
Argo CD detects drift
 │
 ▼
selfHeal
 │
 ▼
replicas: 1 again
```

The cluster is therefore not the source of truth.

Git is.

Manual changes may be useful for temporary debugging, but they are not durable configuration.

---

## 🧹 Pruning

The application also uses:

```yaml
prune: true
```

If a resource is removed from the Git-managed Kustomize output, Argo CD removes the
corresponding managed resource from the cluster.

```text
Resource exists in Git
        │
        ▼
Argo CD manages it
        │
        │ Git commit removes resource
        ▼
Argo CD detects removal
        │
        ▼
Resource is pruned from Kubernetes
```

This prevents Git-managed resources from accumulating as orphaned cluster state.

---

# 🐳 The Important Boundary: Images vs Manifests

Argo CD deploys **Kubernetes state**.

It does not build application code.

This distinction matters for local development.

```text
Application Code Change
        │
        ▼
Build container image
        │
        ▼
Load image into Minikube
        │
        ▼
Image available locally
        │
        ▼
Argo CD can run workloads
```

Argo CD does not perform:

```text
Java source
   │
   ▼
mvn package
   │
   ▼
docker build
   │
   ▼
minikube image load
```

That remains the responsibility of the development workflow.

---

## Manifest-only changes

For changes such as:

- ConfigMap values
- resource limits
- Deployment configuration
- Kustomize patches
- new Services
- new datastore manifests
- environment variables

the GitOps workflow is:

```text
Change manifest
      │
      ▼
git commit
      │
      ▼
git push
      │
      ▼
Argo CD detects desired-state change
      │
      ▼
Cluster reconciles
```

No deployment script needs to be rerun just to apply a manifest change.

---

## Application code changes

For a service code change:

```text
Change Java code
       │
       ▼
Build container image
       │
       ▼
Load image into Minikube
       │
       ▼
Restart / update workload as needed
```

The local images referenced by `overlays/dev` must exist inside Minikube before the
workloads can start.

For this reason:

> **Argo CD replaces manual manifest application. It does not replace the local image build and load workflow.**

---

# 🌍 Environment Scope

## Development

The current Argo CD Application manages:

```text
infra/k8s/overlays/dev
```

This is the self-contained Minikube environment.

```text
Git
 │
 ▼
Argo CD
 │
 ▼
overlays/dev
 │
 ▼
Minikube
```

---

## Future staging and production environments

There is currently no Argo CD `Application` for staging or production because there are no
corresponding Kubernetes overlays yet.

The environment model should evolve together:

```text
infra/k8s/overlays/
│
├── dev/
├── staging/      ← future
└── production/   ← future
```

With matching applications:

```text
infra/argocd/
│
├── application-dev.yaml
├── application-staging.yaml       ← future
└── application-production.yaml    ← future
```

New environments should follow the same pattern as `application-dev.yaml`, changing the
environment-specific source path and target revision as required.

See [Deployment Architecture](../../docs/architecture/08-deployment-architecture.md) for the
broader environment model.

---

# 🔵 Why AKS Is Not Managed Here

The repository also contains:

```text
infra/k8s/overlays/aks
```

This overlay targets the Azure Kubernetes Service environment.

It intentionally does **not** have an Argo CD `Application`.

The AKS deployment is currently managed by:

```text
.github/workflows/ci.yml
```

The workflow performs:

```text
Source
  │
  ▼
Build images
  │
  ▼
Push images
  │
  ▼
Set deployment image references
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

The runtime `platform-secrets` values are applied separately from GitHub Actions secrets.

Adding an Argo CD Application that self-heals the same resources would create two competing
deployment mechanisms:

```text
GitHub Actions                         Argo CD
      │                                   │
      ▼                                   ▼
kubectl apply                     Reconcile from Git
      │                                   │
      └───────────────┬───────────────────┘
                      ▼
                 Same cluster
```

With `selfHeal` enabled, Argo CD could revert changes made imperatively by the workflow if
those changes are not represented in Git.

A proper GitOps migration for AKS would instead use a flow such as:

```text
CI
│
├── Test
├── Build
└── Push immutable image
          │
          ▼
Registry
          │
          ▼
Update Git deployment state
          │
          ▼
Commit image tag / digest
          │
          ▼
Argo CD
          │
          ▼
AKS
```

That requires an image write-back or image-updater-style mechanism.

It is not simply a matter of copying `application-dev.yaml`.

See [`../k8s/README.md`](../k8s/README.md) for the Kubernetes deployment architecture and the
AKS environment's current delivery model.

---

# 🚀 Bootstrap on Minikube

Before Argo CD manages the platform, the local images must exist inside Minikube.

Run:

```bash
./infra/k8s/scripts/deploy-to-minikube.sh
```

This performs the initial local image build and load workflow.

Then install Argo CD and bootstrap the GitOps configuration.

## 1. Install Argo CD

Argo CD itself is a cluster prerequisite and is not vendored into this repository.

```bash
kubectl create namespace argocd

kubectl apply \
  -n argocd \
  -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml

kubectl -n argocd rollout status deployment/argocd-server
```

The ownership model is the same as the Ingress controller:

```text
This repository
    │
    ├── Owns application manifests
    ├── Owns Argo CD Application definitions
    └── Does not vendor cluster-level controller installations
```

The cluster administrator installs and manages Argo CD itself.

---

## 2. Create the AppProject

```bash
kubectl apply -f infra/argocd/project.yaml
```

Verify:

```bash
kubectl -n argocd get appprojects
```

---

## 3. Create the development Application

```bash
kubectl apply -f infra/argocd/application-dev.yaml
```

Verify:

```bash
kubectl -n argocd get applications
```

---

## 4. Watch the initial sync

```bash
kubectl -n argocd get application healthcare-platform-dev -w
```

A healthy application should eventually converge to a state similar to:

```text
SYNC STATUS:    Synced
HEALTH STATUS:  Healthy
```

If the application cannot start its workloads, first verify that the required images were
built and loaded into Minikube.

---

# 🖥️ Accessing Argo CD

Port-forward the Argo CD server:

```bash
kubectl -n argocd port-forward svc/argocd-server 8081:443
```

Open:

```text
https://localhost:8081
```

The initial username is:

```text
admin
```

Retrieve the initial password:

```bash
argocd admin initial-password -n argocd
```

After the first login, remove the initial administrator password secret:

```bash
kubectl -n argocd delete secret argocd-initial-admin-secret
```

Use this only after the initial credentials have been successfully used and the administrator
access path has been established.

---

# 🛠️ Day-to-Day Workflow

## Changing Kubernetes configuration

For a manifest-only change:

```text
Edit manifest
     │
     ▼
git diff
     │
     ▼
git commit
     │
     ▼
git push
     │
     ▼
Argo CD syncs
```

Typical examples:

```text
✓ Change resource limits
✓ Change a ConfigMap value
✓ Add an environment variable
✓ Update a Kustomize patch
✓ Add a Kubernetes Service
✓ Add a local datastore
```

---

## Changing application code

For a service code change:

```text
Edit service
      │
      ▼
Build image
      │
      ▼
Load image into Minikube
      │
      ▼
Restart / roll out workload
```

The full local deployment script can be used again when convenient:

```bash
./infra/k8s/scripts/deploy-to-minikube.sh
```

The script remains useful for image changes.

The GitOps configuration remains responsible for manifest reconciliation.

---

# 🔄 Manual Sync

Argo CD automatically reconciles the application.

A manual sync is useful when you want to apply the current Git state immediately.

## Using the Argo CD CLI

```bash
argocd app sync healthcare-platform-dev
```

## Without the Argo CD CLI

Trigger an operation through Kubernetes:

```bash
kubectl -n argocd patch application healthcare-platform-dev \
  --type merge \
  -p '{"operation":{"sync":{}}}'
```

Watch the application:

```bash
kubectl -n argocd get application healthcare-platform-dev -w
```

---

# 🔍 Troubleshooting

## Application is `OutOfSync`

Check the resources Argo CD believes differ from Git:

```bash
kubectl -n argocd get application healthcare-platform-dev
```

Common causes include:

- a recent Git change has not reconciled yet
- a resource was manually edited
- a managed resource was manually deleted
- the generated Kustomize output differs from the running state

With `selfHeal: true`, ordinary drift should eventually be corrected automatically.

---

## Application is `Degraded`

Check the affected workloads:

```bash
kubectl -n healthcare-platform get pods
```

Inspect logs:

```bash
kubectl -n healthcare-platform logs deployment/<service>
```

Describe a failing pod:

```bash
kubectl -n healthcare-platform describe pod <pod-name>
```

Remember that Argo CD can successfully apply the desired manifests while an application pod
itself is unhealthy.

```text
Argo CD Sync = deployment state applied

Application Health = workloads successfully running
```

These are related but separate concerns.

---

## `ImagePullBackOff`

For the local Minikube environment, verify that the image exists:

```bash
minikube image ls | grep healthcare-platform
```

If the image is missing, rebuild and load it through the deployment workflow:

```bash
./infra/k8s/scripts/deploy-to-minikube.sh
```

Argo CD cannot build or load the missing image for you.

---

# 📚 Related Documentation

- [`docs/infrastructure/gitops.md`](../../docs/infrastructure/gitops.md) — complete GitOps architecture and reconciliation flow
- [`../k8s/README.md`](../k8s/README.md) — Kubernetes environment architecture
- [ADR-0005 — GitOps with Argo CD](../../docs/adr/0005-gitops-with-argocd.md)
- [Deployment Architecture](../../docs/architecture/08-deployment-architecture.md)
- [`docs/operations/README.md`](../../docs/operations/README.md) — operational workflows and CI/CD
- [`../../PROGRESS.md`](../../PROGRESS.md) — platform implementation progress

---

> **Quick rule:** build images where the environment can access them, store deployment state in
> Git, and let Argo CD reconcile Kubernetes to that state.