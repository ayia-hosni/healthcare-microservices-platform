# 🚀 CI/CD Architecture

> Part of [Operations](README.md).

```text
Commit ──► Compile ──► Unit Tests ──► Integration Tests (Testcontainers)
        ──► Static Analysis ──► Docker Build ──► Container Registry ──► Deployment
```

Integration tests use Testcontainers against real infrastructure dependencies instead of
relying exclusively on mocks. The workflow is at `.github/workflows/ci.yml`.

Deployment itself is GitOps-driven via ArgoCD, not a manual `kubectl apply` — see
[`../infrastructure/gitops.md`](../infrastructure/gitops.md) for how the reconcile loop works
and its known limitations.
