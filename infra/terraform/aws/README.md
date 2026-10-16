# Terraform AWS Deployment Guide

This directory contains the Terraform configuration for deploying the healthcare platform infrastructure on **AWS**.

The AWS infrastructure is designed to mirror the Azure deployment:

```text
Terraform
   │
   ├── 10-network
   │      └── VPC, subnets, NAT, security groups, endpoints
   │
   ├── 20-platform
   │      └── EKS, ECR, IAM/IRSA, AWS Load Balancer Controller
   │
   ├── 30-data
   │      └── PostgreSQL, Redis, S3, Secrets Manager, optional MSK
   │
   └── 40-cicd
          └── GitHub Actions OIDC + IAM role
```

Terraform provisions the **AWS infrastructure only**.

The Kubernetes workloads are deployed separately using:

* [`infra/helm`](../../helm) — Helm charts
* [`infra/k8s`](../../k8s) — Kubernetes manifests / Kustomize

The intended boundary is:

> **Terraform → EKS + managed infrastructure → ready for Helm/Kubernetes deployment**

---

## Table of Contents

* [Architecture](#architecture)
* [Directory Structure](#directory-structure)
* [Prerequisites](#prerequisites)
* [AWS Authentication](#aws-authentication)
* [Step 1 — Create the Terraform State Backend](#step-1--create-the-terraform-state-backend)
* [Step 2 — Configure the Network](#step-2--configure-the-network)
* [Step 3 — Create EKS and Platform Infrastructure](#step-3--create-eks-and-platform-infrastructure)
* [Step 4 — Create Data Services](#step-4--create-data-services)
* [Step 5 — Configure GitHub Actions](#step-5--configure-github-actions)
* [Step 6 — Configure kubectl](#step-6--configure-kubectl)
* [Step 7 — Deploy the Application](#step-7--deploy-the-application)
* [Kafka: Self-hosted vs MSK](#kafka-self-hosted-vs-msk)
* [RDS Network Access](#rds-network-access)
* [Verification](#verification)
* [Important Outputs](#important-outputs)
* [Teardown](#teardown)
* [Troubleshooting](#troubleshooting)
* [Security Notes](#security-notes)
* [Cost Considerations](#cost-considerations)

---

# Architecture

The AWS deployment is divided into four Terraform layers.

```text
                              Internet
                                  │
                                  ▼
                           ┌─────────────┐
                           │     AWS     │
                           │     VPC     │
                           └──────┬──────┘
                                  │
             ┌────────────────────┼────────────────────┐
             │                    │                    │
             ▼                    ▼                    ▼
       Public Subnets       Private Subnets       S3 Endpoint
             │                    │
             │                    ▼
             │             ┌─────────────┐
             │             │    EKS      │
             │             │   Cluster   │
             │             └──────┬──────┘
             │                    │
             │          ┌─────────┼─────────┐
             │          ▼         ▼         ▼
             │       Services   Kafka    RabbitMQ
             │
             ▼
       Internet Gateway
             │
             ▼
           NAT GW
             │
             ▼
       AWS managed services
             │
       ┌─────┼──────────────┐
       ▼     ▼              ▼
      RDS   Redis      Secrets Manager
```

The Kubernetes application consists of:

* 9 Spring Boot microservices
* GraphQL Gateway
* Angular frontend
* Kafka
* RabbitMQ
* Elasticsearch
* Redis

Terraform does **not** deploy those application workloads directly.

---

# Directory Structure

```text
aws/
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
│   ├── eks/
│   ├── ecr/
│   ├── data/
│   ├── storage/
│   ├── secrets/
│   └── cicd/
│
└── envs/
    └── dev/
        ├── 10-network/
        ├── 20-platform/
        ├── 30-data/
        └── 40-cicd/
```

The layers intentionally have explicit dependencies:

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

Apply them in this order:

```text
10-network → 20-platform → 30-data
                         │
                         └── 40-cicd
```

`40-cicd` can be applied after `20-platform`.

---

# Prerequisites

Before starting, install:

* AWS CLI
* Terraform
* kubectl
* Helm
* Git

Verify the tools:

```bash
aws --version
terraform version
kubectl version --client
helm version
git --version
```

You also need an AWS account with sufficient permissions to create:

* VPC/networking resources
* EKS
* EC2
* IAM roles/policies
* ECR
* RDS
* ElastiCache
* S3
* Secrets Manager
* CloudWatch
* optionally MSK

> **Important:** This deployment creates billable AWS resources.

For a first deployment, review the cost estimate in `AWS_DEPLOYMENT_PLAN.md` before applying.

---

# AWS Authentication

Configure the AWS CLI:

```bash
aws configure
```

Then verify which AWS account Terraform will use:

```bash
aws sts get-caller-identity
```

You should see something similar to:

```json
{
  "Account": "123456789012",
  "Arn": "arn:aws:iam::123456789012:user/..."
}
```

Make sure this is the AWS account where you actually want to deploy the platform.

---

# Step 1 — Create the Terraform State Backend

Terraform state should not be stored locally for this deployment.

The `bootstrap` configuration creates the S3 bucket used as the remote state backend.

Go to:

```bash
cd bootstrap
```

Initialize Terraform:

```bash
terraform init
```

Create the state bucket:

```bash
terraform apply \
  -var="state_bucket_name=healthcare-platform-tfstate-<unique-suffix>"
```

For example:

```bash
terraform apply \
  -var="state_bucket_name=healthcare-platform-tfstate-aya-2026"
```

The bucket name must be globally unique.

After the deployment completes, retrieve the backend configuration:

```bash
terraform output backend_config_snippet
```

The output will contain the backend configuration that should be used by the four environment layers.

Each layer should use a **different state key**.

For example:

```text
envs/dev/10-network/terraform.tfstate
envs/dev/20-platform/terraform.tfstate
envs/dev/30-data/terraform.tfstate
envs/dev/40-cicd/terraform.tfstate
```

> If you change `state_bucket_name`, make sure `tf_state_bucket` and the corresponding `backend.tf` configuration in every layer use the new bucket.

---

# Step 2 — Configure the Network

The network layer creates the AWS VPC and foundational networking.

Go to:

```bash
cd ../envs/dev/10-network
```

Create your variables file:

```bash
cp terraform.tfvars.example terraform.tfvars
```

Review:

```bash
vim terraform.tfvars
```

Pay particular attention to:

```hcl
admin_ip_cidrs
eks_public_access_cidrs
```

These control who can access infrastructure endpoints from the internet.

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

After successful deployment:

```bash
terraform output
```

The network layer creates:

* VPC
* 2 public subnets
* 2 private subnets
* Internet Gateway
* optional NAT Gateway
* S3 Gateway Endpoint
* shared data security group

---

# Step 3 — Create EKS and Platform Infrastructure

The platform layer creates the Kubernetes infrastructure.

Go to:

```bash
cd ../20-platform
```

Create the variables file:

```bash
cp terraform.tfvars.example terraform.tfvars
```

Review the configuration:

```bash
vim terraform.tfvars
```

Then initialize:

```bash
terraform init
```

Always review the plan before applying:

```bash
terraform plan
```

Apply:

```bash
terraform apply
```

This layer creates:

* EKS cluster
* EKS OIDC provider
* EKS node groups
* ECR repositories
* EKS add-ons
* IAM/IRSA roles
* EBS CSI permissions
* AWS Load Balancer Controller permissions
* CloudWatch permissions
* Container Insights log groups
* EKS control-plane logging

The default node groups are:

```text
system
apps (optional)
```

The `system` node group hosts Kubernetes/system workloads.

The optional `apps` node group can be used for application workloads.

---

# Step 4 — Create Data Services

The data layer provisions the persistent AWS services required by the application.

Go to:

```bash
cd ../30-data
```

Create the variables file:

```bash
cp terraform.tfvars.example terraform.tfvars
```

Review:

```bash
vim terraform.tfvars
```

Then:

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

This layer can create:

### PostgreSQL

Amazon RDS PostgreSQL with separate databases/roles for the microservices.

Conceptually:

```text
RDS PostgreSQL
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

ElastiCache Redis for caching and application coordination.

### S3

Application storage, including the EMR attachments bucket.

### Secrets Manager

Application credentials and generated secrets.

### Kafka

Kafka is optional.

See [Kafka: Self-hosted vs MSK](#kafka-self-hosted-vs-msk).

---

# Step 5 — Configure GitHub Actions

The CI/CD layer connects GitHub Actions to AWS using OIDC.

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
AWS IAM Role
      │
      ├── ECR push
      │
      └── EKS access
```

Retrieve the role ARN:

```bash
terraform output github_actions_role_arn
```

Add that value to the GitHub repository as:

```text
AWS_ROLE_ARN
```

The GitHub Actions workflow can then authenticate to AWS without storing long-lived AWS access keys.

---

# Step 6 — Configure kubectl

After the EKS cluster has been created, configure your local kubeconfig.

Go to the platform layer:

```bash
cd ../20-platform
```

Get the cluster name:

```bash
terraform output -raw eks_cluster_name
```

Then configure kubectl:

```bash
aws eks update-kubeconfig \
  --region <region> \
  --name "$(terraform output -raw eks_cluster_name)"
```

Verify access:

```bash
kubectl get nodes
```

You should see the EKS worker nodes.

Then:

```bash
kubectl get pods -A
```

At this point, the AWS Kubernetes platform is ready.

---

# Step 7 — Deploy the Application

Terraform stops at the infrastructure boundary.

The application should now be deployed through Helm/Kubernetes.

The deployment process is conceptually:

```text
Terraform
   │
   ▼
AWS Infrastructure
   │
   ▼
EKS
   │
   ▼
Helm / Kubernetes
   │
   ├── GraphQL Gateway
   ├── Patient Service
   ├── Doctor Service
   ├── Appointment Service
   ├── Identity Service
   ├── EMR Service
   ├── Billing Service
   ├── Notification Service
   ├── Audit Service
   └── Analytics Service
```

The application deployment needs outputs from the Terraform layers.

### From `20-platform`

Useful outputs include:

```text
eks_cluster_name
ecr_repository_urls
workload_iam_role_arn
alb_controller_iam_role_arn
```

### From `30-data`

Useful outputs include:

```text
rds_address
redis_primary_endpoint
emr_attachments_bucket
secretsmanager_path_prefix
```

These values should be passed to Helm/Kubernetes configuration.

For example:

```text
Terraform
   │
   ├── RDS endpoint
   ├── Redis endpoint
   ├── S3 bucket
   └── Secrets Manager prefix
            │
            ▼
      Helm values
            │
            ▼
      Kubernetes Pods
```

If using External Secrets Operator, configure it to retrieve application secrets from AWS Secrets Manager rather than copying credentials into Kubernetes manifests.

---

# Kafka: Self-hosted vs MSK

Kafka has two supported deployment modes.

Configure the same value in both:

```text
20-platform
30-data
```

using:

```hcl
kafka_mode = "self-hosted"
```

or:

```hcl
kafka_mode = "msk"
```

## Option 1 — Self-hosted Kafka

```hcl
kafka_mode = "self-hosted"
```

Terraform creates no Kafka infrastructure.

Kafka runs inside Kubernetes together with:

* RabbitMQ
* Elasticsearch

This is the default option.

```text
EKS
│
├── Kafka
├── RabbitMQ
└── Elasticsearch
```

Advantages:

* No additional MSK cost
* Consistent with the existing Kubernetes deployment
* Simple for a proof of concept

Approximate additional AWS cost:

```text
$0
```

The actual Kafka infrastructure still consumes EKS compute resources.

---

## Option 2 — Amazon MSK

```hcl
kafka_mode = "msk"
```

Terraform creates:

* Amazon MSK cluster
* SASL/SCRAM configuration
* Secrets Manager secret

All application services use the shared Kafka credential.

Terraform does **not** create Kafka topics.

Topics should be created separately by the application/deployment process.

Approximate cost:

```text
~$150+/month
```

depending on the MSK configuration and AWS region.

Before switching to MSK, update the application Kafka configuration as described in:

```text
modules/data/msk.tf
```

The `20-platform` layer also needs the same `kafka_mode` value so the workload IAM policy includes the appropriate MSK Secrets Manager permissions.

---

# RDS Network Access

By default:

```hcl
db_publicly_accessible = true
```

This is intentional for the current deployment workflow.

It allows Terraform running from your workstation to connect to PostgreSQL and execute the PostgreSQL provider configuration that creates:

* per-service databases
* per-service roles
* database permissions

Access is restricted by the security group to:

```hcl
admin_ip_cidrs
```

This does **not** mean PostgreSQL is open to the entire internet.

The intended flow is:

```text
Your workstation
      │
      │ allowed admin IP
      ▼
   RDS PostgreSQL
```

Redis and MSK remain private.

---

## Production / PHI environments

For real healthcare/PHI workloads, set:

```hcl
db_publicly_accessible = false
```

Then Terraform must run from somewhere that has private network access to the RDS instance.

For example:

```text
Your workstation
      │
      ▼
AWS SSM Session Manager
      │
      ▼
Private VPC
      │
      ▼
RDS
```

Do not expose production databases publicly simply to make Terraform convenient.

---

# Verification

After all Terraform layers have been applied, verify each layer.

## Verify Terraform

```bash
terraform output
```

Run this from each layer:

```text
10-network
20-platform
30-data
40-cicd
```

---

## Verify AWS identity

```bash
aws sts get-caller-identity
```

---

## Verify EKS

```bash
kubectl get nodes
```

Then:

```bash
kubectl get pods -A
```

---

## Verify ECR

```bash
aws ecr describe-repositories
```

---

## Verify RDS

From the AWS CLI:

```bash
aws rds describe-db-instances
```

---

## Verify Redis

Check the ElastiCache deployment from AWS.

The Redis endpoint should only be reachable from the VPC/network where the application runs.

---

## Verify Secrets Manager

```bash
aws secretsmanager list-secrets
```

Do not print secret values to the terminal or commit them to Git.

---

# Important Outputs

The Terraform layers expose outputs that are needed by later deployment stages.

## `10-network`

Important outputs:

```text
vpc_id
public_subnet_ids
private_subnet_ids
data_security_group_id
```

---

## `20-platform`

Important outputs:

```text
eks_cluster_name
eks_cluster_endpoint
ecr_repository_urls
workload_iam_role_arn
alb_controller_iam_role_arn
```

---

## `30-data`

Important outputs:

```text
rds_address
redis_primary_endpoint
emr_attachments_bucket
secretsmanager_path_prefix
```

---

## `40-cicd`

Important output:

```text
github_actions_role_arn
```

Set this value in GitHub as:

```text
AWS_ROLE_ARN
```

---

# Terraform State

Terraform state contains sensitive information.

Even though the backend uses S3 with encryption, assume that Terraform state is sensitive.

The state can contain:

* resource IDs
* infrastructure configuration
* generated credentials
* database information
* Secrets Manager values

Therefore:

* Do not commit `.tfstate` files.
* Do not commit `.terraform/`.
* Do not expose state files publicly.
* Restrict access to the state bucket.
* Use encryption.
* Give only trusted users access to the bucket.

---

# Teardown

**Warning:** Destroying the infrastructure can permanently delete application data.

Before destroying the environment, verify that you actually want to remove:

* RDS
* Redis
* EKS
* ECR
* S3
* Secrets Manager
* MSK

For RDS, set:

```hcl
db_deletion_protection = false
```

in:

```text
envs/dev/30-data/terraform.tfvars
```

Then destroy the layers in reverse order:

```text
40-cicd
   ↓
30-data
   ↓
20-platform
   ↓
10-network
```

Commands:

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

The current configuration uses:

```text
ECR: force_delete
RDS: skip_final_snapshot
```

These are suitable for a POC/development environment.

For production, change these settings so accidental destruction does not result in permanent data loss.

---

# Troubleshooting

## Terraform cannot access AWS

Run:

```bash
aws sts get-caller-identity
```

If this fails, fix your AWS credentials before running Terraform.

---

## Terraform cannot access the state bucket

Check:

```bash
aws s3 ls
```

Then verify that the bucket configured in each `backend.tf` matches the bucket created by `bootstrap`.

If you changed:

```text
state_bucket_name
```

update:

```text
tf_state_bucket
```

and the backend configuration in every layer.

---

## EKS was created but kubectl cannot connect

Run:

```bash
aws eks update-kubeconfig \
  --region <region> \
  --name <cluster-name>
```

Then:

```bash
kubectl get nodes
```

Also verify that the AWS identity used by kubectl has an EKS access entry.

---

## RDS PostgreSQL provider cannot connect

Check:

1. `db_publicly_accessible`
2. `admin_ip_cidrs`
3. RDS security-group rules
4. Your current public IP
5. Network ACLs
6. Whether RDS has finished becoming available

For example, check your public IP:

```bash
curl https://checkip.amazonaws.com
```

Make sure the resulting IP is allowed by `admin_ip_cidrs`.

---

## Pods cannot access AWS services

Check the workload IRSA configuration.

The important pieces are:

```text
EKS OIDC provider
       │
       ▼
IAM role
       │
       ▼
Kubernetes ServiceAccount
       │
       ▼
Application Pod
```

Verify:

```bash
kubectl get serviceaccounts -A
```

Then inspect the relevant service account:

```bash
kubectl describe serviceaccount <service-account>
```

---

## Application cannot connect to RDS/Redis

Check:

```text
VPC
 ├── private subnet
 │      └── EKS node
 │             │
 │             ├── RDS
 │             └── Redis
```

Verify:

* security groups
* subnet routing
* endpoint values
* Kubernetes secrets
* application configuration

Do not make RDS/Redis public as a troubleshooting shortcut.

---

# Security Notes

This Terraform configuration is designed for development/POC deployment and should be hardened before production.

Important areas to review:

### RDS

Current default:

```hcl
db_publicly_accessible = true
```

For production:

```hcl
db_publicly_accessible = false
```

---

### Terraform State

Treat the S3 state bucket as sensitive infrastructure.

Restrict access and enable encryption.

---

### Secrets

Secrets are stored in AWS Secrets Manager but may also exist in Terraform state.

Therefore:

> Secrets Manager does not make the Terraform state non-sensitive.

Protect the state bucket accordingly.

---

### EKS Access

EKS authentication uses:

```text
API_AND_CONFIG_MAP
```

The Terraform caller receives cluster-admin access.

The GitHub Actions role created by `40-cicd` also receives EKS access.

For additional engineers, create additional:

```text
aws_eks_access_entry
```

and corresponding access policies rather than sharing AWS credentials.

---

# Cost Considerations

The biggest cost drivers are expected to be:

```text
EKS / EC2
RDS PostgreSQL
NAT Gateway
ElastiCache
MSK (if enabled)
Load Balancer
CloudWatch logs
```

The most significant optional cost decision is Kafka.

### Self-hosted Kafka

```text
MSK cost: $0
```

Kafka runs on EKS nodes.

### MSK

```text
MSK: ~$150+/month
```

depending on configuration and region.

For a POC, self-hosted Kafka is therefore the cheaper option.

For production, evaluate MSK based on:

* operational overhead
* availability requirements
* Kafka workload
* scaling
* monitoring
* backup/recovery
* security requirements

See:

[`AWS_DEPLOYMENT_PLAN.md`](../../../AWS_DEPLOYMENT_PLAN.md)

for the detailed phased deployment plan and cost estimates.

---

# Complete Deployment Flow

For a new AWS environment, the complete workflow is:

```bash
# 1. Authenticate
aws configure
aws sts get-caller-identity

# 2. Create remote state
cd bootstrap
terraform init
terraform apply \
  -var="state_bucket_name=healthcare-platform-tfstate-<unique-suffix>"

# 3. Network
cd ../envs/dev/10-network
cp terraform.tfvars.example terraform.tfvars
terraform init
terraform plan
terraform apply

# 4. EKS + platform
cd ../20-platform
cp terraform.tfvars.example terraform.tfvars
terraform init
terraform plan
terraform apply

# 5. Data services
cd ../30-data
cp terraform.tfvars.example terraform.tfvars
terraform init
terraform plan
terraform apply

# 6. GitHub Actions
cd ../40-cicd
terraform init
terraform plan
terraform apply

# 7. Configure kubectl
cd ../20-platform
aws eks update-kubeconfig \
  --region <region> \
  --name "$(terraform output -raw eks_cluster_name)"

# 8. Verify EKS
kubectl get nodes
kubectl get pods -A

# 9. Deploy application
cd ../../../
# Use infra/helm or infra/k8s
```

---

# Deployment Boundary

The final architecture intentionally separates **infrastructure provisioning** from **application deployment**:

```text
                    ┌─────────────────────────┐
                    │        Terraform        │
                    │                         │
                    │ VPC                     │
                    │ EKS                     │
                    │ ECR                     │
                    │ RDS                     │
                    │ Redis                   │
                    │ S3                      │
                    │ Secrets Manager         │
                    │ IAM / IRSA              │
                    │ GitHub OIDC             │
                    └────────────┬────────────┘
                                 │
                                 ▼
                    ┌─────────────────────────┐
                    │          EKS            │
                    │                         │
                    │   Infrastructure ready  │
                    └────────────┬────────────┘
                                 │
                                 ▼
                    ┌─────────────────────────┐
                    │    Helm / Kubernetes    │
                    │                         │
                    │ Spring Boot services    │
                    │ GraphQL Gateway         │
                    │ Angular frontend        │
                    │ Kafka                   │
                    │ RabbitMQ                │
                    │ Elasticsearch            │
                    └─────────────────────────┘
```

**Terraform prepares the platform. Helm/Kubernetes deploys the application.**

This separation keeps infrastructure lifecycle and application lifecycle independent while allowing the application deployment to consume Terraform outputs.
