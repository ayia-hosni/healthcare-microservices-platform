# Terraform Azure Deployment Guide

This directory contains the Terraform configuration for deploying the healthcare platform infrastructure on **Microsoft Azure**.

The Azure deployment is designed to provide the same application platform as the AWS deployment while using Azure-native managed services where appropriate.

Terraform provisions the **cloud infrastructure** and prepares the AKS cluster for application deployment.

It does **not** deploy the application workloads themselves.

The application is deployed separately using:

* [`infra/helm`](../../helm) — Helm charts
* [`infra/k8s`](../../k8s) — Kubernetes manifests / Kustomize

The intended boundary is:

> **Terraform → AKS + managed infrastructure → ready for Helm/Kubernetes deployment**

---

## Table of Contents

* [Architecture](#architecture)
* [Directory Structure](#directory-structure)
* [What Terraform Creates](#what-terraform-creates)
* [What Remains in Kubernetes](#what-remains-in-kubernetes)
* [Prerequisites](#prerequisites)
* [Azure Authentication](#azure-authentication)
* [Step 1 — Create the Terraform State Backend](#step-1--create-the-terraform-state-backend)
* [Step 2 — Configure the Network](#step-2--configure-the-network)
* [Step 3 — Create AKS and Platform Infrastructure](#step-3--create-aks-and-platform-infrastructure)
* [Step 4 — Create Data Services](#step-4--create-data-services)
* [Step 5 — Configure GitHub Actions](#step-5--configure-github-actions)
* [Step 6 — Configure kubectl](#step-6--configure-kubectl)
* [Step 7 — Deploy the Application](#step-7--deploy-the-application)
* [Application Changes Required](#application-changes-required)
* [Kafka → Event Hubs](#kafka--event-hubs)
* [MinIO → Azure Blob Storage](#minio--azure-blob-storage)
* [Network Access](#network-access)
* [Verification](#verification)
* [Important Outputs](#important-outputs)
* [Teardown](#teardown)
* [Troubleshooting](#troubleshooting)
* [Security Notes](#security-notes)
* [Complete Deployment Flow](#complete-deployment-flow)

---

# Architecture

The Azure deployment is divided into four Terraform layers:

```text
                              Internet
                                  │
                                  ▼
                         ┌─────────────────┐
                         │      Azure      │
                         │       VNet      │
                         └────────┬────────┘
                                  │
              ┌───────────────────┼───────────────────┐
              │                   │                   │
              ▼                   ▼                   ▼
        Public/egress        AKS subnet          Azure services
          resources               │                   │
              │                   ▼          ┌────────┼────────┐
              │             ┌───────────┐    ▼        ▼        ▼
              │             │    AKS    │  Postgres Redis   Event Hubs
              │             │           │
              │             │ Spring    │
              │             │ services  │
              │             │ RabbitMQ  │
              │             │ Elastic   │
              │             └───────────┘
              │
              ▼
         NAT Gateway
              │
              ▼
        Stable public
        egress IP
```

The platform consists of:

```text
AKS
│
├── Identity Service
├── Patient Service
├── Doctor Service
├── Appointment Service
├── EMR Service
├── Billing Service
├── Notification Service
├── Audit Service
├── Analytics Service
├── GraphQL Gateway
├── Angular Frontend
├── RabbitMQ
└── Elasticsearch
```

Azure-managed services provide:

```text
Azure
│
├── PostgreSQL Flexible Server
├── Azure Cache for Redis
├── Event Hubs
├── Blob Storage
├── Key Vault
├── Container Registry
└── Log Analytics / Container Insights
```

---

# Directory Structure

```text
azure/
│
├── README.md
│
├── bootstrap/
│   ├── main.tf
│   ├── variables.tf
│   └── outputs.tf
│
├── modules/
│   ├── network/
│   ├── monitoring/
│   ├── registry/
│   ├── keyvault/
│   ├── aks/
│   ├── data/
│   ├── storage/
│   └── cicd/
│
└── envs/
    └── dev/
        ├── 10-network/
        ├── 20-platform/
        ├── 30-data/
        └── 40-cicd/
```

The Terraform configuration is intentionally split into independent state layers.

```text
10-network
     │
     ▼
20-platform
     │
     ▼
30-data

40-cicd
     │
     └── depends on 20-platform
```

Apply the layers in this order:

```text
10-network → 20-platform → 30-data
                         │
                         └── 40-cicd
```

`40-cicd` can be applied any time after `20-platform`.

---

# What Terraform Creates

| Concern       | Azure Resource                                | Replaces                                   |
| ------------- | --------------------------------------------- | ------------------------------------------ |
| Compute       | AKS                                           | Local Docker Compose / Kubernetes compute  |
| Images        | Azure Container Registry                      | Local image builds                         |
| PostgreSQL    | Azure Database for PostgreSQL Flexible Server | PostgreSQL container                       |
| Cache         | Azure Cache for Redis                         | Redis container                            |
| Kafka         | Azure Event Hubs Kafka endpoint               | Kafka + ZooKeeper                          |
| EMR storage   | Azure Storage Account + Blob Container        | MinIO                                      |
| Secrets       | Azure Key Vault                               | Kubernetes secrets / environment variables |
| Observability | Log Analytics + Container Insights            | Prometheus/Grafana/Zipkin containers       |

The AKS cluster uses:

* Azure CNI Overlay
* Cilium
* Workload Identity Federation
* Azure RBAC
* system node pool
* optional autoscaling application node pool

---

# What Remains in Kubernetes

Not every component is replaced by an Azure managed service.

The following continue to run inside AKS:

```text
AKS
│
├── RabbitMQ
└── Elasticsearch
```

Azure does not provide a direct equivalent that this application currently uses for these components.

Deploy them using the existing AKS Kubernetes configuration:

```text
infra/k8s/overlays/aks
```

Therefore the final architecture is:

```text
                 Azure
                   │
        ┌──────────┴──────────┐
        │                     │
     Managed               AKS
     Services                │
        │                    ├── Spring Boot services
        │                    ├── GraphQL Gateway
        │                    ├── Angular frontend
        │                    ├── RabbitMQ
        │                    └── Elasticsearch
        │
        ├── PostgreSQL
        ├── Redis
        ├── Event Hubs
        ├── Blob Storage
        └── Key Vault
```

---

# Prerequisites

Install the following tools:

* Azure CLI
* Terraform
* kubectl
* Helm
* Git

Verify:

```bash
az version
terraform version
kubectl version --client
helm version
git --version
```

You also need an Azure subscription with permission to create:

* Resource Groups
* Virtual Networks
* NAT Gateway
* AKS
* Azure Container Registry
* Key Vault
* PostgreSQL Flexible Server
* Azure Cache for Redis
* Event Hubs
* Storage Accounts
* Log Analytics
* Microsoft Entra applications / service principals
* Azure RBAC assignments

> **Important:** Azure resources created by this Terraform configuration may incur charges.

Before applying, review the deployment plan and expected costs.

---

# Azure Authentication

Login to Azure:

```bash
az login
```

List your subscriptions:

```bash
az account list --output table
```

Select the subscription to use:

```bash
az account set --subscription <subscription-id>
```

Verify:

```bash
az account show
```

You should confirm that the selected subscription is the one where you want to deploy the platform.

---

# Step 1 — Create the Terraform State Backend

The bootstrap configuration creates the Azure Storage Account used for Terraform state.

Go to:

```bash
cd bootstrap
```

Initialize:

```bash
terraform init
```

Create the state storage account:

```bash
terraform apply \
  -var="storage_account_name=sthcptfstate<yourinitials><random>"
```

Azure Storage Account names must be globally unique and follow Azure naming requirements.

After creation:

```bash
terraform output backend_config_snippet
```

Use the generated backend configuration in each Terraform layer.

Each layer should have a separate state key.

For example:

```text
envs/dev/10-network/terraform.tfstate
envs/dev/20-platform/terraform.tfstate
envs/dev/30-data/terraform.tfstate
envs/dev/40-cicd/terraform.tfstate
```

> The bootstrap state itself remains local. The bootstrap creates the remote storage location used by the remaining layers.

---

# Step 2 — Configure the Network

The network layer creates the foundational Azure networking.

Go to:

```bash
cd ../envs/dev/10-network
```

Create the variables file:

```bash
cp terraform.tfvars.example terraform.tfvars
```

Open it:

```bash
vim terraform.tfvars
```

Review the network configuration, especially:

```hcl
admin_ip_cidrs
```

This controls which administrator IP addresses can access the Azure-managed services that use public firewall rules.

Initialize Terraform:

```bash
terraform init
```

Review the plan:

```bash
terraform plan
```

Apply:

```bash
terraform apply
```

This layer creates:

* Resource Group
* VNet
* AKS subnet
* Network Security Group
* NAT Gateway
* stable outbound public IP

The NAT Gateway is important because Azure-managed services can use its public IP when creating firewall allow-lists.

Conceptually:

```text
AKS
 │
 ▼
NAT Gateway
 │
 ▼
Stable public IP
 │
 ├── PostgreSQL firewall
 ├── Redis firewall
 ├── Storage firewall
 └── Event Hubs firewall
```

---

# Step 3 — Create AKS and Platform Infrastructure

The platform layer creates the Kubernetes platform.

Go to:

```bash
cd ../20-platform
```

Create your variables:

```bash
cp terraform.tfvars.example terraform.tfvars
```

Review:

```bash
vim terraform.tfvars
```

Initialize:

```bash
terraform init
```

Review:

```bash
terraform plan
```

Apply:

```bash
terraform apply
```

This layer creates:

* Log Analytics workspace
* Azure Container Registry
* Azure Key Vault
* Key Vault RBAC permissions
* AKS cluster
* system node pool
* optional application node pool
* Azure CNI Overlay networking
* Cilium
* workload identity federation
* required managed identities / RBAC configuration

The AKS authentication model uses:

```text
Azure RBAC
+
Workload Identity Federation
```

rather than storing long-lived Azure credentials inside Kubernetes.

---

# Step 4 — Create Data Services

The data layer creates the persistent Azure services.

Go to:

```bash
cd ../30-data
```

Create the variables:

```bash
cp terraform.tfvars.example terraform.tfvars
```

Review:

```bash
vim terraform.tfvars
```

Initialize:

```bash
terraform init
```

Review:

```bash
terraform plan
```

Apply:

```bash
terraform apply
```

This layer creates:

### PostgreSQL

Azure Database for PostgreSQL Flexible Server.

Each microservice receives its own database and login role.

Conceptually:

```text
PostgreSQL Flexible Server
│
├── identity DB
├── patient DB
├── doctor DB
├── appointment DB
├── emr DB
├── billing DB
├── notification DB
├── audit DB
└── analytics DB
```

### Redis

Azure Cache for Redis.

### Event Hubs

Azure Event Hubs provides the Kafka-compatible messaging endpoint.

The current configuration creates:

```text
1 Event Hub
      ↓
per Kafka topic
```

### Blob Storage

Azure Storage Account + Blob Container for EMR attachments.

### Key Vault Secrets

The data layer writes application secrets into Key Vault, including values such as:

```text
<service>-db-password
redis-primary-key
eventhub-connection-string
storage-account-key
jwt-secret
...
```

The exact names should be taken from the Terraform outputs/configuration rather than hard-coded in application code.

---

# Step 5 — Configure GitHub Actions

The CI/CD layer provides GitHub Actions access to Azure without storing long-lived Azure credentials.

Go to:

```bash
cd ../40-cicd
```

Initialize:

```bash
terraform init
```

Review:

```bash
terraform plan
```

Apply:

```bash
terraform apply
```

This creates:

```text
GitHub Actions
      │
      │ OIDC
      ▼
Microsoft Entra Application
      │
      ├── ACR Push
      │
      └── AKS RBAC
```

Retrieve the outputs:

```bash
terraform output
```

You will need values such as:

```text
github_actions_client_id
github_actions_tenant_id
github_actions_subscription_id
```

Configure them as GitHub repository variables.

This allows GitHub Actions to:

1. Authenticate to Azure.
2. Build application images.
3. Push images to ACR.
4. Authenticate to AKS.
5. Deploy the application.

---

# Step 6 — Configure kubectl

After AKS is created, retrieve the Kubernetes credentials.

The resource group comes from `10-network`:

```bash
cd ../10-network

terraform output -raw resource_group_name
```

The AKS cluster name comes from `20-platform`:

```bash
cd ../20-platform

terraform output -raw aks_cluster_name
```

You can configure kubeconfig with:

```bash
az aks get-credentials \
  --resource-group "$(cd ../10-network && terraform output -raw resource_group_name)" \
  --name "$(terraform output -raw aks_cluster_name)" \
  --overwrite-existing
```

Verify access:

```bash
kubectl get nodes
```

Then:

```bash
kubectl get pods -A
```

If the nodes appear, your local `kubectl` is connected to AKS.

---

# Step 7 — Deploy the Application

Terraform has now finished its responsibility.

The next step is Kubernetes application deployment.

The application should be deployed using:

```text
infra/helm
```

and/or:

```text
infra/k8s
```

The deployment flow is:

```text
Terraform
    │
    ▼
Azure Infrastructure
    │
    ▼
AKS
    │
    ▼
Helm / Kubernetes
    │
    ├── Identity Service
    ├── Patient Service
    ├── Doctor Service
    ├── Appointment Service
    ├── EMR Service
    ├── Billing Service
    ├── Notification Service
    ├── Audit Service
    ├── Analytics Service
    ├── GraphQL Gateway
    ├── Angular Frontend
    ├── RabbitMQ
    └── Elasticsearch
```

The application configuration needs outputs from Terraform.

### From `20-platform`

```text
aks_cluster_name
acr_login_server
key_vault_uri
workload_identity_client_id
```

### From `30-data`

```text
postgres_fqdn
redis_hostname
eventhubs_namespace_fqdn
storage_account_name
```

Plus the Key Vault secret names/paths created by Terraform.

### From `40-cicd`

```text
github_actions_client_id
github_actions_tenant_id
github_actions_subscription_id
```

---

# Application Changes Required

Two application-level changes are required when moving from the local deployment to Azure.

These changes cannot be completed by Terraform alone.

They are application/configuration changes in `backend/`.

---

# Kafka → Event Hubs

Azure Event Hubs provides a Kafka-compatible endpoint.

The existing application uses Kafka directly, so the Kafka configuration needs to be changed when deploying to Azure.

The relevant Terraform configuration is:

```text
modules/data/main.tf
```

Event Hubs Kafka authentication uses a SAS connection string.

The Kafka username must be:

```text
$ConnectionString
```

The current Terraform configuration therefore creates one shared Send + Listen namespace authorization rule for the application services.

This matches the current application's shared Kafka credential model.

---

## Application configuration

For each service using Kafka, update:

```yaml
spring:
  kafka:
    bootstrap-servers: <eventhubs_namespace_fqdn>:9093
```

Configure SASL authentication using the Event Hubs connection string stored in Key Vault:

```yaml
spring:
  kafka:
    properties:
      sasl.jaas.config: >
        org.apache.kafka.common.security.plain.PlainLoginModule required
        username="$ConnectionString"
        password="<eventhub-connection-string>";
```

The exact YAML formatting should follow the existing Spring Kafka configuration in the service.

---

## Remove Kafka truststore configuration

The local Kafka deployment currently uses the application's truststore.

For Event Hubs, remove the application-specific:

```text
ssl.truststore.*
```

configuration and the:

```text
kafka.truststore.p12
```

mount.

Event Hubs presents a publicly trusted TLS certificate, so the custom truststore is not required.

---

# MinIO → Azure Blob Storage

The local environment uses MinIO for EMR attachments.

The current `emr-service` uses the MinIO Java SDK / S3 API.

Azure Blob Storage does not expose the same S3 API.

Therefore Terraform creates the Azure Storage Account and container, but the application requires a storage-client change.

The relevant infrastructure configuration is:

```text
modules/storage/main.tf
```

The application component that needs changing is:

```text
emr-service
```

specifically:

```text
DocumentStorageClient
```

The application should use the Azure Blob Storage SDK:

```text
com.azure:azure-storage-blob
```

instead of the MinIO/S3 client.

The resulting architecture becomes:

```text
EMR Service
     │
     ▼
Azure Blob Storage
     │
     └── EMR attachments
```

---

# Network Access

The current Azure deployment deliberately uses:

```text
Public network access
+
IP firewall allow-lists
```

for:

* PostgreSQL
* Redis
* Storage
* Event Hubs

Private Link is not currently used.

The allowed administrator IPs come from:

```hcl
admin_ip_cidrs
```

The AKS workload traffic exits through the NAT Gateway's stable public IP.

Conceptually:

```text
                    AKS
                     │
                     ▼
                NAT Gateway
                     │
               Stable IP
                     │
       ┌─────────────┼──────────────┐
       ▼             ▼              ▼
      RDS           Redis       Event Hubs
       │
       └── firewall allow-list
```

This approach was chosen because private access would require additional:

* delegated subnets
* Private Endpoints
* Private DNS Zones
* DNS configuration
* VNet integration

It would also affect how the Terraform PostgreSQL provider connects during `30-data`.

---

## Production / PHI environments

For real healthcare/PHI workloads, the public-access design should be reviewed and hardened.

The preferred production architecture should move toward private connectivity where practical:

```text
AKS
 │
 ├── Private PostgreSQL
 ├── Private Redis
 ├── Private Storage
 └── Private Event Hubs
```

with appropriate:

* Private Endpoints
* Private DNS
* network segmentation
* restricted NSGs
* least-privilege RBAC

Do not treat the current public-firewall configuration as the final production security architecture.

See:

```text
docs/cloud/azure/
```

for the broader Azure architecture.

---

# Verification

After applying all layers, verify the deployment.

## Verify Azure authentication

```bash
az account show
```

---

## Verify Terraform

From each layer:

```bash
terraform output
```

Check:

```text
10-network
20-platform
30-data
40-cicd
```

---

## Verify AKS

```bash
kubectl get nodes
```

Then:

```bash
kubectl get pods -A
```

---

## Verify ACR

```bash
az acr list --output table
```

You should see the registry created by `20-platform`.

---

## Verify PostgreSQL

```bash
az postgres flexible-server list --output table
```

The server should be running and reachable according to the configured firewall rules.

---

## Verify Redis

```bash
az redis list --output table
```

---

## Verify Event Hubs

```bash
az eventhubs namespace list --output table
```

---

## Verify Storage

```bash
az storage account list --output table
```

---

## Verify Key Vault

```bash
az keyvault list --output table
```

Do not print secret values to the terminal.

---

# Important Outputs

## `10-network`

Important outputs:

```text
resource_group_name
vnet_id
aks_subnet_id
nat_gateway_public_ip
```

---

## `20-platform`

Important outputs:

```text
aks_cluster_name
acr_login_server
key_vault_uri
workload_identity_client_id
```

---

## `30-data`

Important outputs:

```text
postgres_fqdn
redis_hostname
eventhubs_namespace_fqdn
storage_account_name
```

The data layer also creates the Key Vault secrets consumed by the application.

---

## `40-cicd`

Important outputs:

```text
github_actions_client_id
github_actions_tenant_id
github_actions_subscription_id
```

These values should be configured in GitHub repository variables.

---

# Terraform State

Terraform state is sensitive.

Even though the state is stored in Azure Storage, it can contain:

* infrastructure configuration
* resource IDs
* generated passwords
* database information
* Key Vault secret values

Therefore:

* Never commit `.tfstate` files.
* Never commit `.terraform/`.
* Restrict access to the Terraform state Storage Account.
* Enable storage encryption.
* Do not expose the state account publicly.
* Treat Terraform state as production-sensitive data.

> Storing a secret in Key Vault does not mean the secret is absent from Terraform state.

---

# AKS Authentication

AKS is configured with:

```text
local_account_disabled = true
```

This disables the local Kubernetes administrator account.

Access should therefore use Azure identity and Azure RBAC.

The expected authentication flow is:

```text
Developer / CI
      │
      ▼
Microsoft Entra ID
      │
      ▼
Azure RBAC
      │
      ▼
AKS
```

The `20-platform` layer grants the Terraform caller:

```text
Azure Kubernetes Service RBAC Cluster Admin
```

The `40-cicd` layer grants the same level of AKS access to the GitHub Actions identity.

For additional engineers, grant the appropriate Azure Kubernetes Service RBAC role to their identities rather than sharing credentials.

---

# Kubernetes Version

The Kubernetes version defaults to:

```text
null
```

and Terraform ignores subsequent version changes.

This is intentional.

Kubernetes upgrades should be deliberate rather than occurring unexpectedly during unrelated Terraform applies.

Perform AKS upgrades explicitly using Azure's AKS upgrade process.

---

# Teardown

**Warning:** Destroying the environment can permanently delete application data.

Before destroying the deployment, verify whether you need to preserve:

* PostgreSQL data
* Redis data
* Blob Storage files
* Event Hubs
* Key Vault secrets
* Container images
* Terraform state

Destroy the Terraform layers in reverse dependency order:

```text
40-cicd
    ↓
30-data
    ↓
20-platform
    ↓
10-network
```

For example:

```bash
cd envs/dev/40-cicd
terraform destroy
```

Then:

```bash
cd ../30-data
terraform destroy
```

Then:

```bash
cd ../20-platform
terraform destroy
```

Finally:

```bash
cd ../10-network
terraform destroy
```

---

## Key Vault warning

Key Vault uses:

```text
purge_protection_enabled
```

When the vault is destroyed, it enters a recoverable/deleted state rather than immediately disappearing permanently.

This can prevent reuse of the same vault name for the retention period.

If recreating the environment immediately, be aware of this behavior.

---

# Troubleshooting

## Azure CLI authentication fails

Run:

```bash
az login
```

Then:

```bash
az account show
```

Make sure the correct subscription is selected:

```bash
az account set --subscription <subscription-id>
```

---

## Terraform cannot access the backend

Verify that the Azure Storage Account created by `bootstrap` exists.

Check:

```bash
az storage account list --output table
```

Then verify that each layer's `backend.tf` points to the correct storage account and state key.

---

## Key Vault access denied

If Terraform receives a Key Vault authorization error, verify:

1. The Terraform identity has the required Key Vault RBAC role.
2. The Key Vault network/firewall rules allow the current administrator IP.
3. `admin_ip_cidrs` contains your current public IP.
4. Azure RBAC propagation has completed.

The `20-platform` and `30-data` layers both interact with Key Vault.

---

## PostgreSQL provider cannot connect

If `30-data` fails while creating databases/roles, check:

1. PostgreSQL public access configuration.
2. `admin_ip_cidrs`.
3. PostgreSQL firewall rules.
4. Your current public IP.
5. PostgreSQL server availability.
6. Network routing.

Check your public IP:

```bash
curl https://checkip.amazonaws.com
```

The returned IP must be allowed by the configured firewall rules.

> For Azure deployments, the command is only being used to determine your public IP; it does not require AWS credentials or AWS infrastructure.

---

## AKS kubeconfig fails

Retrieve credentials again:

```bash
az aks get-credentials \
  --resource-group <resource-group> \
  --name <aks-cluster-name> \
  --overwrite-existing
```

Then:

```bash
kubectl get nodes
```

If authentication still fails, verify that your Azure identity has an appropriate AKS RBAC role.

---

## Pods cannot access Key Vault

The expected authentication flow is:

```text
Kubernetes ServiceAccount
          │
          ▼
Workload Identity
          │
          ▼
Microsoft Entra ID
          │
          ▼
Azure Managed Identity
          │
          ▼
Key Vault
```

Check the Kubernetes service account and workload identity configuration.

The application pod should not contain a long-lived Azure client secret.

---

## Pods cannot pull images from ACR

Verify:

```text
AKS kubelet identity
       │
       ▼
ACR
       │
       └── AcrPull
```

Check that the AKS identity has `AcrPull` permission on the registry.

---

## Kafka/Event Hubs connection fails

Check:

* Event Hubs namespace hostname.
* Port `9093`.
* SASL mechanism.
* Username is exactly `$ConnectionString`.
* Connection string comes from Key Vault.
* TLS configuration.
* The application is no longer trying to use the local Kafka truststore.

---

## EMR attachments fail

If `emr-service` cannot upload/download files, verify:

* Storage Account name.
* Blob container name.
* Storage credentials/identity.
* Azure Blob SDK configuration.
* That the application has been migrated from the MinIO client.

The MinIO S3 client cannot be used directly against Azure Blob Storage.

---

# Security Notes

This configuration is suitable for development/POC deployment but should be hardened before production.

## Secrets

Secrets are stored in Key Vault, but generated values may also exist in Terraform state.

Protect both:

```text
Key Vault
    +
Terraform State
```

---

## AKS

The local Kubernetes admin account is disabled:

```text
local_account_disabled = true
```

Use Azure identity and RBAC.

---

## Network

The current design uses public network access with IP firewall rules for several managed services.

For production healthcare workloads, evaluate private connectivity and network isolation.

---

## Identity

Use Workload Identity Federation rather than embedding Azure credentials into application containers.

The desired model is:

```text
Pod
 │
 ▼
Service Account
 │
 ▼
Workload Identity
 │
 ▼
Azure Identity
 │
 ▼
Azure Resource
```

---

# Complete Deployment Flow

For a new Azure environment, the complete workflow is:

```bash
# 1. Authenticate
az login
az account set --subscription <subscription-id>
az account show

# 2. Create Terraform state backend
cd bootstrap
terraform init

terraform apply \
  -var="storage_account_name=sthcptfstate<yourinitials><random>"

terraform output backend_config_snippet

# 3. Create network
cd ../envs/dev/10-network
cp terraform.tfvars.example terraform.tfvars

terraform init
terraform plan
terraform apply

# 4. Create AKS + platform
cd ../20-platform
cp terraform.tfvars.example terraform.tfvars

terraform init
terraform plan
terraform apply

# 5. Create managed data services
cd ../30-data
cp terraform.tfvars.example terraform.tfvars

terraform init
terraform plan
terraform apply

# 6. Create GitHub Actions identity
cd ../40-cicd

terraform init
terraform plan
terraform apply

# 7. Configure kubectl
az aks get-credentials \
  --resource-group "$(cd ../10-network && terraform output -raw resource_group_name)" \
  --name "$(cd ../20-platform && terraform output -raw aks_cluster_name)" \
  --overwrite-existing

# 8. Verify AKS
kubectl get nodes
kubectl get pods -A

# 9. Deploy application
cd ../../../

# Use infra/helm and/or infra/k8s
```

---

# Deployment Boundary

The architecture deliberately separates **cloud infrastructure** from **application deployment**.

```text
                    ┌──────────────────────────┐
                    │        Terraform         │
                    │                          │
                    │ Resource Group            │
                    │ VNet                      │
                    │ NAT Gateway               │
                    │ AKS                       │
                    │ ACR                       │
                    │ PostgreSQL                │
                    │ Redis                     │
                    │ Event Hubs                │
                    │ Blob Storage               │
                    │ Key Vault                  │
                    │ Log Analytics              │
                    │ GitHub OIDC                │
                    └────────────┬─────────────┘
                                 │
                                 ▼
                    ┌──────────────────────────┐
                    │           AKS            │
                    │                          │
                    │   Infrastructure ready   │
                    └────────────┬─────────────┘
                                 │
                                 ▼
                    ┌──────────────────────────┐
                    │    Helm / Kubernetes     │
                    │                          │
                    │ Spring Boot services     │
                    │ GraphQL Gateway           │
                    │ Angular frontend          │
                    │ RabbitMQ                  │
                    │ Elasticsearch             │
                    └──────────────────────────┘
```

**Terraform prepares Azure and AKS. Helm/Kubernetes deploys the application.**

The Azure-specific application integrations are:

```text
Kafka      → Azure Event Hubs
MinIO      → Azure Blob Storage
Secrets    → Azure Key Vault
Postgres   → Azure PostgreSQL Flexible Server
Redis      → Azure Cache for Redis
Images     → Azure Container Registry
```

This keeps infrastructure lifecycle and application lifecycle independent while allowing the Kubernetes deployment to consume the outputs produced by Terraform.
