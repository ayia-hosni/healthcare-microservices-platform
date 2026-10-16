# Terraform

> Full detail lives in [`infra/terraform/README.md`](../../infra/terraform/README.md) and the
> per-cloud READMEs it links — this page is a pointer, not a second copy.

Terraform provisions the cloud infrastructure this platform runs on, one tree per target. It
does **not** replace `infra/k8s`/`infra/helm` — those still deploy the ten Spring Boot
services and the Angular frontend onto the cluster Terraform creates. Terraform's job stops at
"cluster + managed data services + registry + secret store provisioned," not "application
running."

Both trees use **component modules composed by layered environments** — each layer
(`10-network`, `20-platform`, `30-data`, `40-cicd`) is its own root module with its own
remote state, reading upstream layers via `terraform_remote_state`.

```text
infra/terraform/
├── README.md              # index + layer table + AWS/Azure parity
├── azure/                 # real, provisioned
│   ├── bootstrap/         #   remote state backend (Storage container)
│   ├── modules/           #   network, monitoring, registry, keyvault, aks, data, storage, cicd
│   └── envs/dev/{10-network,20-platform,30-data,40-cicd}/
└── aws/                   # designed; runbook in AWS_DEPLOYMENT_PLAN.md
    ├── bootstrap/         #   remote state backend (S3 bucket + DynamoDB lock)
    ├── modules/           #   network, eks, ecr, data, storage, secrets, cicd
    └── envs/dev/{10-network,20-platform,30-data,40-cicd}/
```

The two trees are independent and share no state. Apply layers `10 → 20 → 30`, with `40` any
time after `20`. See [`infra/terraform/azure/README.md`](../../infra/terraform/azure/README.md)
/ [`infra/terraform/aws/README.md`](../../infra/terraform/aws/README.md) for the module-by-module
detail and the outputs each layer exposes, and
[`../cloud/azure/compute.md`](../cloud/azure/compute.md#infrastructure-as-code) /
[`../cloud/aws/compute.md`](../cloud/aws/compute.md#infrastructure-as-code) for how each fits
its cloud architecture.
