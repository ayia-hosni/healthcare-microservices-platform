# ArgoCD (GitOps)

Continuous deployment for `infra/k8s/overlays/dev`, driven by this repo instead of manual
`kubectl apply -k`. See [ADR-0005](../../docs/adr/0005-gitops-with-argocd.md) for why, and
[`../../GITOPS.md`](../../GITOPS.md) for the fuller picture with diagrams — this file is the
short day-to-day reference.

## Layout

- **`project.yaml`** — an `AppProject` scoping ArgoCD to this repo and the
  `healthcare-platform` namespace only.
- **`application-dev.yaml`** — an `Application` pointing at `infra/k8s/overlays/dev`
  (the same kustomize overlay `deploy-to-minikube.sh` applies), with automated sync
  (`prune` + `selfHeal`): once bootstrapped, a `git push` to `main` is what ships a change,
  and any manual `kubectl edit`/`delete` against the cluster gets reverted back to match
  git on ArgoCD's next reconcile.

There's no `staging`/`prod` `Application` yet, because there's no `staging`/`prod` overlay
yet (see [Deployment Architecture](../../docs/architecture/08-deployment-architecture.md)).
Add one of each together, following `application-dev.yaml` as the template — only `path`
and `targetRevision` should need to change.

Like the Ingress *controller* (see `infra/k8s/README.md`), ArgoCD itself isn't vendored
into this repo — install it from the upstream manifest, same as you would `ingress-nginx`.

## Bootstrapping on minikube

Run `infra/k8s/scripts/deploy-to-minikube.sh` first — ArgoCD deploys application manifests,
it doesn't build or load container images into minikube's docker daemon, so the images
`overlays/dev` references still need to exist locally before ArgoCD can bring the
Deployments up. From then on, re-running the script is optional except when you've changed
a service's code and need to rebuild+reload its image; manifest-only changes (a patch, a
ConfigMap value, a new datastore) should go through `git` + ArgoCD sync instead.

```bash
# 1. Install ArgoCD itself (not vendored in this repo)
kubectl create namespace argocd
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml
kubectl -n argocd rollout status deployment/argocd-server

# 2. Bootstrap this repo's project + application
kubectl apply -f infra/argocd/project.yaml
kubectl apply -f infra/argocd/application-dev.yaml

# 3. Watch it sync
kubectl -n argocd get application healthcare-platform-dev -w
```

## Accessing the UI/CLI

```bash
kubectl -n argocd port-forward svc/argocd-server 8081:443
# https://localhost:8081 — user "admin"
argocd admin initial-password -n argocd   # one-time bootstrap password
```

Delete the `admin` initial-password secret (`argocd-initial-admin-secret`) after logging in
once, per ArgoCD's own upstream guidance.

## Forcing a resync without waiting for the poll interval

```bash
argocd app sync healthcare-platform-dev
# or, without the CLI:
kubectl -n argocd patch application healthcare-platform-dev --type merge \
  -p '{"operation":{"sync":{}}}'
```
