#!/usr/bin/env bash
# Builds every backend service image + the frontend image, loads them into minikube's
# internal image store (works regardless of which minikube driver you're using — docker,
# hyperkit, virtualbox, etc.), then applies the dev overlay.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

echo "==> Enabling minikube's ingress addon (no-op if already enabled)"
minikube addons enable ingress

SERVICES=(
  identity-service
  patient-service
  doctor-service
  appointment-service
  emr-service
  billing-service
  notification-service
  audit-service
  analytics-service
  graphql-gateway
)

echo "==> Building backend images (this is a full Maven build per service — first run takes a while)"
for svc in "${SERVICES[@]}"; do
  echo "--> healthcare-platform/${svc}:latest"
  docker build -t "healthcare-platform/${svc}:latest" -f "$REPO_ROOT/backend/${svc}/Dockerfile" "$REPO_ROOT/backend"
done

echo "==> Building frontend image"
docker build -t "healthcare-platform/frontend:latest" "$REPO_ROOT/frontend"

echo "==> Loading images into minikube"
for svc in "${SERVICES[@]}"; do
  minikube image load "healthcare-platform/${svc}:latest"
done
minikube image load "healthcare-platform/frontend:latest"

echo "==> Applying infra/k8s/overlays/dev"
kubectl apply -k "$REPO_ROOT/infra/k8s/overlays/dev"

echo "==> Waiting for deployments to become available (first run can take several minutes while Postgres/Kafka come up)"
kubectl -n healthcare-platform wait --for=condition=available --timeout=600s deployment --all \
  || echo "Some deployments aren't ready yet — check: kubectl -n healthcare-platform get pods"

echo "==> App is served through the Ingress (infra/k8s/base/ingress.yaml), not exposed directly."
echo "In another terminal, run:"
echo "    kubectl -n ingress-nginx port-forward svc/ingress-nginx-controller 8080:80"
echo "then open http://localhost:8080/"
