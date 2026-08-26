# 🛠 Infrastructure

How the platform is containerized, deployed to Kubernetes, and kept in sync via GitOps.

| Document | Covers |
| --- | --- |
| [`docker.md`](docker.md) | Docker Compose — the local development container stack |
| [`kubernetes.md`](kubernetes.md) | Kustomize-based Kubernetes deployments (Minikube + AKS) |
| [`helm.md`](helm.md) | The umbrella Helm chart |
| [`terraform.md`](terraform.md) | Terraform — provisions the real Azure infrastructure |
| [`gitops.md`](gitops.md) | Argo CD — how the reconcile loop works, and its known limitations |

For the manifest/module-level detail behind each of these, see
[`../../infra/k8s/README.md`](../../infra/k8s/README.md),
[`../../infra/terraform/README.md`](../../infra/terraform/README.md), and
[`../../infra/argocd/README.md`](../../infra/argocd/README.md).

For cloud-provider architecture (what all of this deploys onto), see
[`../cloud/README.md`](../cloud/README.md). For day-to-day operational commands, see
[`../operations/README.md`](../operations/README.md).
