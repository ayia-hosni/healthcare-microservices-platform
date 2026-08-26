# ⚓ Helm

> Part of [Infrastructure](README.md).

The project includes an umbrella Helm chart at `infra/helm/healthcare-platform/`. Rather than
hand-writing nine near-identical charts, `values.yaml` holds one entry per service (port, DB
name/user, replica count) that drives a single parameterized `templates/service.yaml`
(Deployment + Service pair). Day-to-day local/dev deployment uses the Kustomize manifests
under `infra/k8s/` (see [`kubernetes.md`](kubernetes.md)); the Helm chart is the path toward a
single-package cluster install.
