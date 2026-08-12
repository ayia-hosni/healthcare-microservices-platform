# Terraform: Azure deployment

Provisions the Azure infrastructure this platform runs on. It does **not** replace
`infra/helm`/`infra/k8s` — those still deploy the 9 Spring Boot services + Angular frontend
onto the cluster this creates. Terraform's job stops at "AKS cluster + managed data services +
secrets in Key Vault, ready for `helm upgrade --install`."

## What gets created

| Concern | Resource | Replaces (docker-compose / dev k8s overlay) |
|---|---|---|
| Compute | AKS (system pool + autoscaling "apps" pool), Azure CNI Overlay + Cilium | `docker-compose.yml` app services / `infra/k8s` |
| Images | Azure Container Registry (Standard, AcrPull via kubelet identity) | local `docker build` |
| Postgres | Flexible Server, 1 DB + 1 login role per microservice | `postgres` container + `init-multiple-dbs.sh` |
| Cache | Azure Cache for Redis | `redis` container |
| Kafka | Event Hubs namespace (Kafka-compatible endpoint), 1 Event Hub per topic | `kafka` + `zookeeper` containers |
| EMR attachments | Storage Account + blob container | `minio` container |
| Secrets | Key Vault (RBAC-authorized), read by pods via workload identity | `secrets.yaml` / `docker-compose` env vars |
| Observability | Log Analytics workspace + AKS Container Insights (`oms_agent`) | `prometheus`/`grafana`/`zipkin` containers |

**Not provisioned — stays self-hosted in-cluster**, since Azure has no direct managed
equivalent: RabbitMQ (used by `notification-service`) and Elasticsearch (present in
`docker-compose.yml` but not currently called from any service's code). Deploy these the same
way the `dev` k8s overlay already does, just running on this AKS cluster instead of wherever
that overlay currently targets.

## Two application-level changes this infra requires (not code — just flagging clearly)

Terraform can't complete these; they're Java/config changes in `backend/`:

1. **Kafka auth model** (`eventhubs.tf`). Today each service authenticates with its own
   SASL/PLAIN username (see `docker-compose.yml`'s
   `KAFKA_LISTENER_NAME_BROKER_PLAIN_SASL_JAAS_CONFIG`), but the broker has no ACL authorizer
   configured — any authenticated service can already read/write any topic, so no real
   per-service isolation exists today to preserve. Event Hubs' Kafka surface authenticates via
   a SAS connection string where the username must literally be `"$ConnectionString"`, which
   doesn't support distinct per-service usernames — so this provisions one shared Send+Listen
   (no Manage) namespace rule for all 9 services, matching the actual current security
   posture. Per-service `application.yml` changes needed on cutover:
   - `spring.kafka.bootstrap-servers`: `<eventhubs_namespace_fqdn output>:9093`
   - `spring.kafka.properties.sasl.jaas.config`: `...PlainLoginModule required username="$ConnectionString" password="<eventhub-connection-string secret from Key Vault>";`
   - Drop the `ssl.truststore.*` lines and the `kafka.truststore.p12` volume mount entirely —
     Event Hubs presents a publicly-trusted CA cert, unlike the throwaway dev CA from
     `infra/docker/kafka/generate-dev-certs.sh`.

2. **MinIO → Blob Storage** (`storage.tf`). `emr-service` talks to MinIO via the MinIO Java SDK
   (`io.minio.MinioClient`) against an S3-style API — Azure Blob Storage does not speak that
   API. This provisions the target storage account/container, but
   `emr-service`'s `MinioConfig`/`DocumentStorageClient` need a follow-up code change to the
   Azure Blob SDK (`com.azure:azure-storage-blob`) before they can use it.

## Design trade-off: public access + firewall allow-lists, not full private networking

Postgres, Redis, Storage, and Event Hubs are deployed with **public network access + IP
firewall rules** (AKS's NAT gateway IP, allow-listed automatically, plus your own IPs via
`admin_ip_cidrs`), not Postgres VNet-integration / Private Link. This was a deliberate scope
call for operability: full private access needs a delegated subnet + private DNS zones, and
the `postgresql` provider connection this config uses to create the 9 per-service
databases/roles (`postgres.tf`) would then only work from inside the VNet — normally meaning a
bastion host, VPN, or self-hosted CI runner, none of which are set up here.

If this is handling real PHI in production, tightening this is the top follow-up:
- Postgres: switch to `delegated_subnet_id` + `private_dns_zone_id` (private access mode; note
  this requires recreating the server, it can't be changed in place).
- Redis/Storage/Event Hubs: add `azurerm_private_endpoint` + matching `privatelink.*` private
  DNS zones linked to the VNet.

## Layout

```
bootstrap/       One-time: creates the storage account that holds envs/*'s remote state.
                 Run once, state stays local here (rarely changes after initial setup).
envs/dev/        The actual infrastructure. Single environment for now — copy this directory
                 (new backend key, new tfvars) to add staging/prod later.
```

## Running it

```bash
az login
az account set --subscription <subscription-id>

# 1. One-time: create the remote state storage account.
cd bootstrap
terraform init
terraform apply
terraform output backend_config_snippet   # paste these 4 values into envs/dev/backend.tf

# 2. The actual infrastructure.
cd ../envs/dev
cp terraform.tfvars.example terraform.tfvars   # fill in admin_ip_cidrs at minimum
terraform init
terraform plan
terraform apply

# 3. Point kubectl/Helm at the new cluster.
az aks get-credentials \
  --resource-group "$(terraform output -raw resource_group_name)" \
  --name "$(terraform output -raw aks_cluster_name)" \
  --overwrite-existing

# 4. Deploy the app (existing Helm chart / kustomize base), wiring its values/secrets to
#    this config's outputs (postgres_fqdn, redis_hostname, eventhubs_namespace_fqdn,
#    storage_account_name, key_vault_uri, workload_identity_client_id, ...) and to the
#    per-service passwords this config wrote into Key Vault (e.g. "identity-db-password",
#    "redis-primary-key", "eventhub-connection-string", "storage-account-key").
```

Local `terraform apply` needs network access to the Postgres server on 5432 (see the
public-access trade-off above) — that's what `admin_ip_cidrs` in `terraform.tfvars` is for.

## Notes

- `local_account_disabled = true` on AKS means `az aks get-credentials` + Azure RBAC
  (`Azure Kubernetes Service RBAC Cluster Admin`, already granted to the identity running
  `terraform apply`) is the only way in — no `az aks get-credentials --admin`. Grant that same
  role to teammates who need cluster access.
- All generated secrets (DB passwords, Redis key, Storage key, Event Hubs connection string,
  JWT secret) are written to Key Vault, not left as plain Terraform outputs — but they do still
  live in Terraform state, so treat the state file (in the bootstrap-created storage account)
  as sensitive.
- `kubernetes_version` defaults to `null` (AKS's current default) and is then
  `ignore_changes`d, so upgrades are a deliberate `az aks upgrade` / AKS auto-upgrade decision,
  not something a stray `terraform apply` does for you.
