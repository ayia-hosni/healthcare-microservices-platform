# Terraform

> Full detail lives in [`infra/terraform/README.md`](../../infra/terraform/README.md) — this
> page is a pointer, not a second copy.

Terraform provisions the real Azure infrastructure this platform runs on (AKS, Azure Database
for PostgreSQL, Azure Cache for Redis, Event Hubs, Key Vault, ACR, a Storage Account). It does
**not** replace `infra/k8s`/`infra/helm` — those still deploy the ten Spring Boot services and
the Angular frontend onto the cluster Terraform creates. Terraform's job stops at
"cluster + managed data services + registry provisioned," not "application running."

```text
infra/terraform/
├── bootstrap/        # remote state backend
└── envs/
    └── dev/           # AKS, Postgres, Redis, Event Hubs, Key Vault, ACR, Storage
```

See [`infra/terraform/README.md`](../../infra/terraform/README.md) for the module-by-module
detail, and [`../cloud/azure/compute.md`](../cloud/azure/compute.md#infrastructure-as-code)
for how this fits into the broader Azure architecture. There is no AWS Terraform
implementation yet — see [`../cloud/aws/README.md`](../cloud/aws/README.md).
