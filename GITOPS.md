# 🔄 GitOps Deployment

> **Git is the source of truth. Argo CD continuously reconciles the Kubernetes cluster to match it.**

This platform uses a GitOps deployment model for Kubernetes environments. Instead of relying on someone manually running `kubectl apply -k` from their laptop, the desired cluster state is stored in Git and continuously reconciled by Argo CD.

**Status: Implemented**

The Argo CD manifests in [`infra/argocd/`](infra/argocd/) are part of the working platform, not a future architecture proposal.

* **Architecture decision:** [ADR-0005 — GitOps with Argo CD](docs/adr/0005-gitops-with-argocd.md)
* **Quick operational reference:** [`infra/argocd/README.md`](infra/argocd/README.md)
* **Platform operations:** [`OPERATIONS.md`](OPERATIONS.md)

This document explains the architecture, reconciliation model, bootstrap process, and current limitations.

---

## Table of Contents

* [Why GitOps?](#-why-gitops)
* [How It Works](#-how-it-works)
* [The Reconciliation Model](#-the-reconciliation-model)
* [Argo CD Architecture](#-argo-cd-architecture)
* [One Application per Environment](#-one-application-per-environment)
* [Two Loops: Images vs. Manifests](#-two-loops-images-vs-manifests)
* [Bootstrapping](#-bootstrapping)
* [Day-to-Day Operations](#-day-to-day-operations)
* [Known Limitations](#-known-limitations)
* [Future Evolution](#-future-evolution)

---

# 🎯 Why GitOps?

Before introducing Argo CD, the development cluster was deployed imperatively:

```bash id="k1v4nx"
./infra/k8s/scripts/deploy-to-minikube.sh
```

That works, but it creates an important operational problem:

> **The cluster state depends on whoever last ran a command.**

With an imperative workflow, Kubernetes has no continuous connection back to the Git commit that describes the intended deployment.

For example:

```text id="ryq5jc"
Git
 │
 │ says replicas = 2
 │
 ▼
Kubernetes Cluster
 │
 │ kubectl edit deployment ...
 ▼
replicas = 5
```

Nothing automatically corrects that change.

The cluster has drifted away from the repository, and unless someone notices and manually redeploys the manifests, the actual state remains different from the intended state.

GitOps changes the model:

```text id="qai1fk"
Git Repository
     │
     │ Desired State
     ▼
┌─────────────────┐
│     Argo CD     │
│                 │
│ Continuous      │
│ Reconciliation  │
└────────┬────────┘
         │
         ▼
Kubernetes Cluster
     │
     │ Actual State
     │
     └──────────────► Compared continuously
```

The key principle becomes:

> **A Git commit defines what the cluster should look like.**

Argo CD continuously compares that desired state with the actual cluster state and reconciles differences automatically.

---

# ⚙️ How It Works

The high-level deployment flow is:

```text id="x0us42"
Developer
   │
   │ git commit
   │ git push origin main
   ▼
┌──────────────────────────────────────────────┐
│                  GitHub                      │
│                                              │
│ infra/k8s/overlays/dev                       │
│                                              │
│ Desired Kubernetes State                     │
└───────────────────────┬──────────────────────┘
                        │
                        │ Argo CD observes Git
                        ▼
┌──────────────────────────────────────────────┐
│                  Argo CD                     │
│                                              │
│  1. Read desired state from Git              │
│  2. Inspect actual cluster state             │
│  3. Detect differences                       │
│  4. Synchronize resources                    │
│  5. Prune resources removed from Git         │
│  6. Heal manually introduced drift           │
└───────────────────────┬──────────────────────┘
                        │
                        ▼
┌──────────────────────────────────────────────┐
│             Kubernetes Cluster               │
│                                              │
│        healthcare-platform namespace         │
└──────────────────────────────────────────────┘
```

Conceptually, Argo CD performs the reconciliation that would otherwise require repeated manual commands:

```text id="kmo1o8"
Desired State ──┐
                ├──► Compare ──► Detect Drift ──► Reconcile
Actual State ───┘
```

The result is a continuously managed cluster rather than a cluster that only changes when a developer remembers to deploy it.

---

# 🔁 The Reconciliation Model

The application's automated synchronization policy enables three important behaviors.

## Automated synchronization

A change committed to the configured Git revision becomes eligible for deployment without manually applying the manifests.

```text id="iq2gc0"
Git commit
    │
    ▼
Desired state changes
    │
    ▼
Argo CD detects difference
    │
    ▼
Automated sync
    │
    ▼
Cluster updated
```

---

## Self-healing

`selfHeal: true` protects the cluster from manual configuration drift.

```text id="8j2ksy"
Git
 │
 │ replicas: 2
 ▼
Cluster
 │
 │
 │ kubectl edit deployment
 ▼
replicas: 5
 │
 │
 ▼
Argo CD detects drift
 │
 ▼
Self-heal reconciliation
 │
 ▼
replicas: 2
```

This means manual cluster changes are not merely detected—they are eventually reverted to match the state declared in Git.

The important implication is:

> **Git wins over manual cluster changes.**

If a resource is modified directly with `kubectl edit`, the change is temporary unless the same change is committed to the repository.

---

## Pruning

`prune: true` handles resources that no longer exist in the desired state.

```text id="ckowza"
Git Commit
    │
    │ Remove Service X from overlay
    ▼
Argo CD detects resource removed
    │
    ▼
Prune enabled?
    │
   Yes
    │
    ▼
Service X deleted from cluster
```

Without pruning, removing a manifest from Git could leave the old resource running in the cluster.

With pruning enabled:

> **If Git no longer declares the resource, Argo CD can remove it from the managed cluster state.**

---

# 🏗️ Argo CD Architecture

The GitOps configuration is organized around an Argo CD `AppProject` and an environment-specific `Application`.

```text id="ljzq7x"
┌────────────────────────────────────────────────────────────────────┐
│                   Argo CD AppProject                              │
│                   healthcare-platform                             │
│                                                                    │
│                   infra/argocd/project.yaml                       │
│                                                                    │
│  Defines the security and deployment boundaries for this project  │
│                                                                    │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │                                                              │  │
│  │          Application: healthcare-platform-dev                │  │
│  │                                                              │  │
│  │          infra/argocd/application-dev.yaml                   │  │
│  │                                                              │  │
│  │  Source                                                     │  │
│  │  └── infra/k8s/overlays/dev @ main                           │  │
│  │                                                              │  │
│  │  Destination                                                │  │
│  │  └── healthcare-platform namespace                          │  │
│  │                                                              │  │
│  │  Sync Policy                                                │  │
│  │  ├── automated                                              │  │
│  │  ├── prune: true                                            │  │
│  │  ├── selfHeal: true                                         │  │
│  │  └── retry with exponential backoff                         │  │
│  │                                                              │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
                 healthcare-platform namespace
```

The project is deliberately scoped to this platform rather than using Argo CD's broad default project.

This provides an explicit boundary around:

* the repository Argo CD is allowed to deploy from
* the Kubernetes namespace it is allowed to target
* the applications belonging to this platform

---

# 🌍 One Application per Environment

The platform uses:

> **One Argo CD Application per environment—not one Application per microservice.**

For the development environment:

```text id="0fmq2f"
Application
└── healthcare-platform-dev
    │
    └── infra/k8s/overlays/dev
        │
        ├── identity-service
        ├── patient-service
        ├── doctor-service
        ├── appointment-service
        ├── emr-service
        ├── billing-service
        ├── notification-service
        ├── audit-service
        ├── analytics-service
        ├── graphql-gateway
        └── supporting infrastructure
```

This matches the platform's existing deployment boundary.

The development overlay is already treated as one deployable unit:

```text id="3krbdb"
infra/k8s/overlays/dev
        │
        ├── kubectl apply -k
        │
        └── deploy-to-minikube.sh
```

Creating a separate Argo CD Application for every service would introduce additional complexity:

```text id="c1omns"
11 Applications
     │
     ├── 11 sync states
     ├── 11 health states
     ├── 11 deployment boundaries
     ├── more ordering concerns
     └── more rollback surface
```

That complexity is not currently justified because the services are deployed together rather than independently.

An app-of-apps architecture remains a possible future evolution if the platform begins supporting independently promoted and deployed services.

---

## Adding new environments

There is currently one GitOps environment:

```text id="5wb92r"
healthcare-platform-dev
```

Future environments follow the same pattern:

```text id="ol7dx9"
infra/k8s/overlays/
├── dev/
├── staging/
└── prod/
```

With corresponding Argo CD Applications:

```text id="7s6m3h"
infra/argocd/
├── application-dev.yaml
├── application-staging.yaml
└── application-prod.yaml
```

The architecture does not need to change when new overlays are introduced.

A new environment primarily means defining:

* the source overlay
* the target namespace or cluster
* the Git revision or promotion strategy
* the environment-specific synchronization policy

---

# 🔀 Two Loops: Images vs. Manifests

One of the most important distinctions in this architecture is:

> **Argo CD reconciles Kubernetes manifests. It does not build application images.**

These are two separate delivery loops.

```text id="qetttf"
                         APPLICATION DELIVERY

 Code Change                                  Manifest Change
 (Java source, business logic)                (ConfigMap, patch, replicas)
      │                                             │
      ▼                                             ▼
 Build Container Image                        Commit Manifest
      │                                             │
      ▼                                             │
 Make Image Available                             │
 to Kubernetes                                     │
      │                                             │
      └──────────────────┐              ┌───────────┘
                         │              │
                         ▼              ▼
                    GitOps Desired State
                           │
                           ▼
                    Argo CD Sync
                           │
                           ▼
                  Kubernetes Deployment
```

---

## Code changes

A code change creates new application behavior, which requires a new container image.

For the current Minikube workflow:

```text id="dx2hpn"
Change service code
        │
        ▼
docker build
        │
        ▼
minikube image load
        │
        ▼
Image available to cluster
        │
        ▼
Deployment can run the new image
```

Argo CD does not perform this build/load process.

The current local workflow still uses:

```bash id="km4qkt"
./infra/k8s/scripts/deploy-to-minikube.sh
```

to build and prepare images for the Minikube environment.

---

## Manifest changes

A manifest-only change follows the GitOps path directly:

```text id="1ah6k3"
Change ConfigMap
       │
       ▼
Change Deployment patch
       │
       ▼
git commit
       │
       ▼
git push origin main
       │
       ▼
Argo CD detects Git change
       │
       ▼
Argo CD syncs cluster
```

Examples include:

* changing environment configuration
* modifying resource requests or limits
* adding a Kubernetes Service
* updating a ConfigMap
* changing ingress configuration
* adding a datastore dependency
* modifying a Kustomize patch

---

## Production evolution

A real production delivery pipeline should connect the two loops through an image registry and immutable image references:

```text id="i16n5r"
Code Change
    │
    ▼
CI Pipeline
    │
    ├── Test
    ├── Build Image
    ├── Scan Image
    └── Push Image
             │
             ▼
      Container Registry
             │
             ▼
 Update Manifest Image Tag
             │
             ▼
           Git Commit
             │
             ▼
           Argo CD
             │
             ▼
       Kubernetes Cluster
```

The missing piece is not Argo CD itself—it is the image publication and image-version promotion mechanism that feeds GitOps.

---

# 🚀 Bootstrapping

The initial setup has two parts:

1. Make the application's container images available to Minikube.
2. Install Argo CD and register the platform as a managed application.

## 1. Prepare the development environment

```bash id="1tw23q"
./infra/k8s/scripts/deploy-to-minikube.sh
```

This step comes first because Argo CD deploys manifests—it does not build or load the local images referenced by the development overlay.

---

## 2. Install Argo CD

Argo CD is a cluster-level prerequisite and is not vendored into this repository.

```bash id="i6lyq2"
kubectl create namespace argocd

kubectl apply \
  -n argocd \
  -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml

kubectl -n argocd rollout status deployment/argocd-server
```

This follows the same infrastructure ownership approach used for cluster-level components such as the ingress controller.

The application repository owns the platform manifests—not the complete lifecycle of every cluster add-on.

---

## 3. Register the GitOps project and application

```bash id="sbzyfu"
kubectl apply -f infra/argocd/project.yaml

kubectl apply -f infra/argocd/application-dev.yaml
```

---

## 4. Watch the initial synchronization

```bash id="2wm4y7"
kubectl -n argocd get application healthcare-platform-dev -w
```

The application should transition toward a healthy, synchronized state.

Conceptually:

```text id="h22lcv"
Application Created
       │
       ▼
Repository Connected
       │
       ▼
Manifests Rendered
       │
       ▼
Resources Compared
       │
       ▼
Resources Applied
       │
       ▼
Application Synced
       │
       ▼
Workloads Healthy
```

---

# 🛠️ Day-to-Day Operations

## Access the Argo CD UI

Port-forward the Argo CD server:

```bash id="7qib7g"
kubectl -n argocd port-forward svc/argocd-server 8081:443
```

Then open:

```text id="lns4g8"
https://localhost:8081
```

Default bootstrap user:

```text id="yymlyf"
admin
```

Retrieve the initial password:

```bash id="29mhb6"
argocd admin initial-password -n argocd
```

After completing the initial login, remove the initial administrator secret according to Argo CD's bootstrap guidance.

---

## Force an immediate synchronization

Normally, Argo CD reconciles automatically.

To trigger synchronization immediately:

```bash id="xnp2ru"
argocd app sync healthcare-platform-dev
```

Or trigger the operation through Kubernetes without using the Argo CD CLI:

```bash id="vibae1"
kubectl -n argocd patch application healthcare-platform-dev \
  --type merge \
  -p '{"operation":{"sync":{}}}'
```

---

## Check application status

```bash id="8u2ug9"
kubectl -n argocd get application healthcare-platform-dev
```

Inspect detailed application state:

```bash id="f46dy7"
kubectl -n argocd describe application healthcare-platform-dev
```

---

# ⚠️ Known Limitations

The GitOps implementation intentionally solves the **manifest reconciliation** problem. It does not solve every deployment concern.

## 1. Image delivery is still separate

The development overlay currently references local images built into the Minikube environment.

```text id="dnxosw"
Code change
    │
    ▼
Build image manually
    │
    ▼
Load image into Minikube
    │
    ▼
Argo CD can reconcile the manifest
```

The current image model uses `:latest`-style local development images with `imagePullPolicy: IfNotPresent`.

A production-grade pipeline should move toward:

```text id="nz9cmv"
Immutable image tag
        +
Container registry
        +
CI build pipeline
        +
Git-based image promotion
```

---

## 2. Plaintext secrets remain plaintext

The current Kubernetes secret manifests still contain values stored in Git.

Examples include:

```text id="4uwex1"
infra/k8s/base/secrets.yaml
```

and the development overlay's `platform-secrets` configuration.

Argo CD will correctly reconcile these resources, but GitOps does not encrypt secrets by itself.

Potential future approaches include:

* Sealed Secrets
* External Secrets Operator
* cloud-managed secret stores
* workload identity
* cloud-native secret integration

This is separate security work and should not be confused with GitOps reconciliation.

---

## 3. Argo CD introduces operational overhead

Argo CD becomes another production component that must be:

* installed
* secured
* monitored
* upgraded
* backed up where required
* configured with appropriate access controls

GitOps reduces manual deployment drift, but it does not eliminate operational responsibility.

The trade-off is intentional:

```text id="5trzkm"
More platform infrastructure
          │
          ▼
More operational responsibility
          │
          ▼
Self-correcting deployments
        +
Auditable desired state
        +
Continuous reconciliation
```

---

## 4. Only one environment is currently implemented

The current GitOps Application targets:

```text id="ohidib"
infra/k8s/overlays/dev
```

There are no GitOps applications for staging or production because corresponding deployment overlays do not yet exist.

The future sequence is:

```text id="mhmn9z"
Create staging/prod overlays
          │
          ▼
Define environment-specific configuration
          │
          ▼
Create Argo CD Applications
          │
          ▼
Add promotion strategy
```

The GitOps pattern itself does not need to change.

---

# 🔮 Future Evolution

The current implementation establishes the reconciliation foundation.

A more mature deployment model could evolve toward:

```text id="pdv0aa"
Developer
    │
    ▼
Pull Request
    │
    ▼
CI
├── Test
├── Build
├── Security Scan
└── Push Immutable Image
          │
          ▼
Container Registry
          │
          ▼
Update Environment Manifest
          │
          ▼
Git Commit / Pull Request
          │
          ▼
Argo CD
          │
          ▼
Development
          │
          ▼
Promotion
          │
          ├──► Staging
          │
          └──► Production
```

Potential next steps include:

1. Introduce immutable image tags.
2. Push images to a container registry.
3. Add automated image version updates.
4. Introduce `staging` and `production` overlays.
5. Replace plaintext secrets with a proper secret-management solution.
6. Define environment promotion and rollback policies.
7. Add deployment health gates and notifications.

---

# 📚 Related Documentation

* [`infra/argocd/README.md`](infra/argocd/README.md) — quick Argo CD commands and local usage
* [`docs/adr/0005-gitops-with-argocd.md`](docs/adr/0005-gitops-with-argocd.md) — GitOps architecture decision
* [`docs/adr/0001-api-gateway-ingress.md`](docs/adr/0001-api-gateway-ingress.md) — ingress ownership and architecture
* [`OPERATIONS.md`](OPERATIONS.md) — build, test, deployment, and operational workflows
* [`PROGRESS.md`](PROGRESS.md) — current platform implementation progress

---

> **GitOps does not mean Git builds your application.**
>
> It means Git declares the desired deployment state, and Argo CD continuously works to make the cluster match it.
