# AWS Manual Deployment Guide

This guide explains how to deploy the healthcare platform to **AWS manually**, without Terraform.

The goal is to build the AWS infrastructure required to run the platform and then deploy the application to EKS using Helm/Kubernetes.

Terraform provides an automated version of this architecture, but this guide intentionally creates each resource manually, with real `aws`/`eksctl`/`kubectl` commands, so that every AWS component and dependency can be understood and reproduced from a terminal — not just clicked through in the Console.

Every command below uses real values from this repository (service names, ports, database names) rather than generic placeholders, so blocks can mostly be copy-pasted in order. Anywhere a value is genuinely yours to choose (account ID, unique bucket suffix, domain name) is marked accordingly.

---

# Architecture

The target architecture is:

```text
                              Internet
                                  │
                                  ▼
                              Route 53
                                  │
                                  ▼
                         Application Load Balancer
                                  │
                                  ▼
                                EKS
                                  │
             ┌────────────────────┼────────────────────┐
             │                    │                    │
             ▼                    ▼                    ▼
       GraphQL Gateway      Spring Boot Services   Frontend
             │                    │
             │          ┌─────────┼─────────┐
             │          ▼         ▼         ▼
             │       Postgres   Redis     Kafka
             │          │                   │
             │          │                   │
             │          ▼                   ▼
             │         RDS                  MSK
             │
             └───────────────────────────────────┐
                                                 │
                         ┌───────────────────────┼──────────────┐
                         ▼                       ▼              ▼
                       S3                  Secrets Manager   CloudWatch
```

Some infrastructure remains self-hosted inside EKS:

```text
EKS
│
├── RabbitMQ
└── Elasticsearch
```

Kafka can also remain self-hosted instead of using MSK.

---

# AWS Services Used

| Purpose               | AWS Service                        |
| --------------------- | ----------------------------------- |
| DNS                   | Route 53                           |
| Network               | VPC                                 |
| Internet access       | Internet Gateway                    |
| Private subnet egress | NAT Gateway                         |
| Kubernetes            | Amazon EKS                          |
| Container images      | Amazon ECR                          |
| PostgreSQL            | Amazon RDS PostgreSQL               |
| Redis                 | Amazon ElastiCache                  |
| Kafka                 | Amazon MSK or self-hosted Kafka     |
| Object storage        | Amazon S3                           |
| Secrets               | AWS Secrets Manager                 |
| Load balancing        | AWS Load Balancer Controller + ALB  |
| Kubernetes storage    | EBS CSI Driver                      |
| Monitoring            | CloudWatch                          |
| Logs                  | CloudWatch Container Insights       |
| Pod AWS permissions   | IAM + IRSA                          |
| CI/CD authentication  | GitHub OIDC + IAM                   |
| Messaging             | RabbitMQ in EKS                     |
| Search                | Elasticsearch in EKS                |

---

# Deployment Order

Create the infrastructure in this order:

```text
1. AWS account / IAM
        ↓
2. VPC
        ↓
3. Subnets
        ↓
4. Internet Gateway
        ↓
5. NAT Gateway
        ↓
6. Route tables
        ↓
7. Security Groups
        ↓
8. ECR
        ↓
9. EKS
        ↓
10. EKS add-ons
        ↓
11. IAM / IRSA
        ↓
12. RDS PostgreSQL
        ↓
13. ElastiCache Redis
        ↓
14. MSK OR self-hosted Kafka
        ↓
15. S3
        ↓
16. Secrets Manager
        ↓
17. AWS Load Balancer Controller
        ↓
18. CloudWatch
        ↓
19. RabbitMQ + Elasticsearch
        ↓
20. Build/push application images
        ↓
21. Deploy application with Helm
        ↓
22. Configure Route 53
        ↓
23. Verify the platform
```

Do not create EKS before the networking is ready. Security group *rules* that reference the EKS
node security group are created later, in the step that owns the resource being protected (RDS,
ElastiCache, MSK) — by then the EKS node security group already exists. Step 10 below only
creates the empty security groups; the actual ingress rules are authorized in Steps 18/20/22.

---

# Step 0 — Install Required Tools

Beyond the AWS CLI, this guide uses two tools that turn multi-step Console workflows (IAM roles,
node groups, OIDC, IRSA) into single commands:

* **eksctl** — the standard CLI for creating/managing EKS clusters. It creates the cluster's IAM
  roles, VPC CNI, and node groups declaratively instead of you wiring each IAM trust policy by
  hand.
* **helm** — used in Step 21 to deploy the application, and in Step 17 to install the AWS Load
  Balancer Controller.

```bash
# macOS
brew install eksctl helm jq

# verify
eksctl version
helm version
jq --version
```

`jq` is used throughout this guide to pull IDs (subnet IDs, security group IDs, ARNs) out of
`aws` CLI JSON output into shell variables, so later commands don't need values pasted by hand.

---

# Step 1 — Configure AWS CLI

Install and configure the AWS CLI.

Verify:

```bash
aws --version
```

Configure credentials:

```bash
aws configure
```

You will be asked for:

```text
AWS Access Key ID
AWS Secret Access Key
Default region
Default output format
```

For example:

```text
Default region: us-east-1
Default output: json
```

Verify the identity:

```bash
aws sts get-caller-identity
```

You should see your:

```text
Account
User/Role
ARN
```

Save the account ID for use in later commands:

```bash
export AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
echo "$AWS_ACCOUNT_ID"
```

---

# Step 2 — Choose an AWS Region

Choose one region and use it consistently.

```bash
export AWS_REGION=us-east-1
```

Verify:

```bash
aws configure get region
```

The same region should contain:

* VPC
* EKS
* ECR
* RDS
* ElastiCache
* MSK
* S3
* Secrets Manager

Every command below assumes `$AWS_REGION` and `$AWS_ACCOUNT_ID` are already exported in your
shell (from Steps 1–2).

---

# Step 3 — Create the VPC

```bash
export VPC_ID=$(aws ec2 create-vpc \
  --cidr-block 10.0.0.0/16 \
  --tag-specifications 'ResourceType=vpc,Tags=[{Key=Name,Value=healthcare-platform-vpc}]' \
  --query 'Vpc.VpcId' --output text)

echo "$VPC_ID"

aws ec2 modify-vpc-attribute --vpc-id "$VPC_ID" --enable-dns-hostnames '{"Value":true}'
aws ec2 modify-vpc-attribute --vpc-id "$VPC_ID" --enable-dns-support '{"Value":true}'
```

The VPC provides the private network for EKS and the managed services.

Conceptually:

```text
10.0.0.0/16
│
├── Public subnet
├── Public subnet
├── Private subnet
└── Private subnet
```

Use at least two Availability Zones:

```bash
export AZ_A="${AWS_REGION}a"
export AZ_B="${AWS_REGION}b"
```

---

# Step 4 — Create Public Subnets

```bash
export SUBNET_PUBLIC_A=$(aws ec2 create-subnet \
  --vpc-id "$VPC_ID" --cidr-block 10.0.1.0/24 --availability-zone "$AZ_A" \
  --tag-specifications 'ResourceType=subnet,Tags=[{Key=Name,Value=healthcare-platform-public-a},{Key=kubernetes.io/role/elb,Value=1}]' \
  --query 'Subnet.SubnetId' --output text)

export SUBNET_PUBLIC_B=$(aws ec2 create-subnet \
  --vpc-id "$VPC_ID" --cidr-block 10.0.2.0/24 --availability-zone "$AZ_B" \
  --tag-specifications 'ResourceType=subnet,Tags=[{Key=Name,Value=healthcare-platform-public-b},{Key=kubernetes.io/role/elb,Value=1}]' \
  --query 'Subnet.SubnetId' --output text)

echo "$SUBNET_PUBLIC_A $SUBNET_PUBLIC_B"

aws ec2 modify-subnet-attribute --subnet-id "$SUBNET_PUBLIC_A" --map-public-ip-on-launch
aws ec2 modify-subnet-attribute --subnet-id "$SUBNET_PUBLIC_B" --map-public-ip-on-launch
```

The `kubernetes.io/role/elb=1` tag is what lets the AWS Load Balancer Controller (Step 17)
auto-discover these subnets as valid targets for an internet-facing ALB.

---

# Step 5 — Create Private Subnets

```bash
export SUBNET_PRIVATE_A=$(aws ec2 create-subnet \
  --vpc-id "$VPC_ID" --cidr-block 10.0.11.0/24 --availability-zone "$AZ_A" \
  --tag-specifications 'ResourceType=subnet,Tags=[{Key=Name,Value=healthcare-platform-private-a},{Key=kubernetes.io/role/internal-elb,Value=1}]' \
  --query 'Subnet.SubnetId' --output text)

export SUBNET_PRIVATE_B=$(aws ec2 create-subnet \
  --vpc-id "$VPC_ID" --cidr-block 10.0.12.0/24 --availability-zone "$AZ_B" \
  --tag-specifications 'ResourceType=subnet,Tags=[{Key=Name,Value=healthcare-platform-private-b},{Key=kubernetes.io/role/internal-elb,Value=1}]' \
  --query 'Subnet.SubnetId' --output text)

echo "$SUBNET_PRIVATE_A $SUBNET_PRIVATE_B"
```

These are where EKS worker nodes and private resources (RDS, ElastiCache, MSK) live.

Conceptually:

```text
VPC
│
├── Public
│   ├── 10.0.1.0/24
│   └── 10.0.2.0/24
│
└── Private
    ├── 10.0.11.0/24
    └── 10.0.12.0/24
```

---

# Step 6 — Create the Internet Gateway

```bash
export IGW_ID=$(aws ec2 create-internet-gateway \
  --tag-specifications 'ResourceType=internet-gateway,Tags=[{Key=Name,Value=healthcare-platform-igw}]' \
  --query 'InternetGateway.InternetGatewayId' --output text)

aws ec2 attach-internet-gateway --internet-gateway-id "$IGW_ID" --vpc-id "$VPC_ID"

echo "$IGW_ID"
```

The Internet Gateway provides Internet connectivity for public subnets.

---

# Step 7 — Create Public Route Table

```bash
export RT_PUBLIC_ID=$(aws ec2 create-route-table \
  --vpc-id "$VPC_ID" \
  --tag-specifications 'ResourceType=route-table,Tags=[{Key=Name,Value=healthcare-platform-public-rt}]' \
  --query 'RouteTable.RouteTableId' --output text)

aws ec2 create-route \
  --route-table-id "$RT_PUBLIC_ID" \
  --destination-cidr-block 0.0.0.0/0 \
  --gateway-id "$IGW_ID"

aws ec2 associate-route-table --route-table-id "$RT_PUBLIC_ID" --subnet-id "$SUBNET_PUBLIC_A"
aws ec2 associate-route-table --route-table-id "$RT_PUBLIC_ID" --subnet-id "$SUBNET_PUBLIC_B"
```

The architecture becomes:

```text
Public subnet
     │
     ▼
Route Table
     │
     ▼
Internet Gateway
     │
     ▼
Internet
```

---

# Step 8 — Create NAT Gateway

The private EKS nodes need outbound Internet access for things such as:

* pulling container images
* contacting AWS APIs
* downloading packages
* external HTTPS requests

```bash
export EIP_ALLOC_ID=$(aws ec2 allocate-address --domain vpc --query 'AllocationId' --output text)

export NAT_GW_ID=$(aws ec2 create-nat-gateway \
  --subnet-id "$SUBNET_PUBLIC_A" \
  --allocation-id "$EIP_ALLOC_ID" \
  --tag-specifications 'ResourceType=natgateway,Tags=[{Key=Name,Value=healthcare-platform-nat}]' \
  --query 'NatGateway.NatGatewayId' --output text)

echo "$NAT_GW_ID"

# NAT gateways take a minute or two to become available before routes can target them
aws ec2 wait nat-gateway-available --nat-gateway-ids "$NAT_GW_ID"
```

---

# Step 9 — Create Private Route Table

```bash
export RT_PRIVATE_ID=$(aws ec2 create-route-table \
  --vpc-id "$VPC_ID" \
  --tag-specifications 'ResourceType=route-table,Tags=[{Key=Name,Value=healthcare-platform-private-rt}]' \
  --query 'RouteTable.RouteTableId' --output text)

aws ec2 create-route \
  --route-table-id "$RT_PRIVATE_ID" \
  --destination-cidr-block 0.0.0.0/0 \
  --nat-gateway-id "$NAT_GW_ID"

aws ec2 associate-route-table --route-table-id "$RT_PRIVATE_ID" --subnet-id "$SUBNET_PRIVATE_A"
aws ec2 associate-route-table --route-table-id "$RT_PRIVATE_ID" --subnet-id "$SUBNET_PRIVATE_B"
```

Now:

```text
Private subnet
      │
      ▼
Private Route Table
      │
      ▼
NAT Gateway
      │
      ▼
Internet Gateway
      │
      ▼
Internet
```

The private nodes do not need public IP addresses.

---

# Step 10 — Create Security Groups

Create the (currently empty) security groups for the data-layer services. Their ingress rules
are added later (Steps 18/20/22) once the EKS node security group exists to reference as the
allowed source — see the note at the end of "Deployment Order" above for why.

```bash
export SG_RDS_ID=$(aws ec2 create-security-group \
  --group-name healthcare-platform-rds-sg --description "RDS PostgreSQL access from EKS" \
  --vpc-id "$VPC_ID" --query 'GroupId' --output text)

export SG_REDIS_ID=$(aws ec2 create-security-group \
  --group-name healthcare-platform-redis-sg --description "ElastiCache Redis access from EKS" \
  --vpc-id "$VPC_ID" --query 'GroupId' --output text)

export SG_MSK_ID=$(aws ec2 create-security-group \
  --group-name healthcare-platform-msk-sg --description "MSK Kafka access from EKS" \
  --vpc-id "$VPC_ID" --query 'GroupId' --output text)

echo "RDS=$SG_RDS_ID  Redis=$SG_REDIS_ID  MSK=$SG_MSK_ID"
```

The ports each will eventually allow, from the EKS node security group only:

```text
EKS node SG
 │
 ├── TCP 5432 → RDS PostgreSQL SG
 ├── TCP 6379 → ElastiCache Redis SG
 └── TCP 9092 → MSK SG   (9096 instead, if using SASL_SCRAM/TLS auth on MSK)
```

Do not expose any of these to `0.0.0.0/0`.

---

# Step 11 — Create ECR Repositories

Create one ECR repository for each application image:

```text
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
frontend
```

```bash
for svc in identity-service patient-service doctor-service appointment-service \
           emr-service billing-service notification-service audit-service \
           analytics-service graphql-gateway frontend; do
  aws ecr create-repository \
    --repository-name "$svc" \
    --image-scanning-configuration scanOnPush=true \
    --region "$AWS_REGION"
done
```

Verify:

```bash
aws ecr describe-repositories --region "$AWS_REGION" \
  --query 'repositories[].repositoryName' --output table
```

---

# Step 12 — Create the EKS Cluster

Using `eksctl` with a cluster config file lets the whole cluster (control plane + IAM roles +
OIDC provider) be created from one command, into the exact VPC/subnets already built above,
instead of manually wiring the EKS cluster IAM role's trust policy by hand.

```bash
cat > eks-cluster.yaml <<EOF
apiVersion: eksctl.io/v1alpha5
kind: ClusterConfig

metadata:
  name: healthcare-platform
  region: ${AWS_REGION}
  version: "1.30"

vpc:
  id: ${VPC_ID}
  subnets:
    public:
      ${AZ_A}: { id: ${SUBNET_PUBLIC_A} }
      ${AZ_B}: { id: ${SUBNET_PUBLIC_B} }
    private:
      ${AZ_A}: { id: ${SUBNET_PRIVATE_A} }
      ${AZ_B}: { id: ${SUBNET_PRIVATE_B} }

iam:
  withOIDC: true
EOF

eksctl create cluster -f eks-cluster.yaml
```

`iam.withOIDC: true` does what Step 16 ("Configure IAM OIDC") used to require as a separate
manual action — the cluster comes out of this command already able to back IRSA roles.

This takes 15–20 minutes. `eksctl` waits for the control plane before returning.

For cluster endpoint access, `eksctl`'s default (public + private, unrestricted CIDR) is fine
for development. For production, add under `vpc:`:

```yaml
vpc:
  clusterEndpoints:
    publicAccess: true
    privateAccess: true
  publicAccessCIDRs: ["<your-office-or-VPN-CIDR>/32"]
```

---

# Step 13 — Create EKS Node Group

```bash
eksctl create nodegroup \
  --cluster healthcare-platform \
  --region "$AWS_REGION" \
  --name system \
  --node-type t3.medium \
  --nodes 2 \
  --nodes-min 2 \
  --nodes-max 4 \
  --node-private-networking \
  --managed
```

`--node-private-networking` places the nodes in the private subnets configured in Step 12's
`eks-cluster.yaml`. `--managed` uses an EKS managed node group, so AMI patching/lifecycle is
handled by AWS rather than by you.

---

# Step 14 — Create an Application Node Group

For larger deployments, create a second node group so system add-ons and application workloads
can scale independently:

```bash
eksctl create nodegroup \
  --cluster healthcare-platform \
  --region "$AWS_REGION" \
  --name apps \
  --node-type t3.large \
  --nodes 2 \
  --nodes-min 1 \
  --nodes-max 5 \
  --node-private-networking \
  --managed
```

Architecture:

```text
EKS
│
├── system node group
│
└── apps node group
    ├── Spring Boot
    ├── GraphQL
    └── Frontend
```

If you want application pods to schedule only onto the `apps` node group, taint it and add a
matching toleration/nodeSelector in the Helm values used in Step 21:

```bash
eksctl create nodegroup --cluster healthcare-platform --name apps \
  --node-type t3.large --nodes 2 --node-private-networking --managed \
  --node-taints "workload=apps:NoSchedule"
```

---

# Step 15 — Configure kubectl

`eksctl create cluster` already updates your kubeconfig, but the explicit command (useful after
switching machines, or if you skipped `eksctl` and created the cluster another way) is:

```bash
aws eks update-kubeconfig \
  --region "$AWS_REGION" \
  --name healthcare-platform
```

Verify:

```bash
kubectl get nodes
```

You should see your EKS worker nodes (system + apps node groups) in `Ready` state.

```bash
kubectl get pods -A
```

---

# Step 16 — Enable EKS Add-ons

```bash
for addon in vpc-cni coredns kube-proxy aws-ebs-csi-driver; do
  aws eks create-addon \
    --cluster-name healthcare-platform \
    --addon-name "$addon" \
    --region "$AWS_REGION" \
    --resolve-conflicts OVERWRITE
done
```

The EBS CSI Driver (`aws-ebs-csi-driver`) allows Kubernetes to create persistent EBS volumes —
it needs an IRSA role, which `eksctl` can attach in one step instead of hand-editing the addon's
service account annotation:

```bash
eksctl create iamserviceaccount \
  --cluster healthcare-platform --region "$AWS_REGION" \
  --namespace kube-system --name ebs-csi-controller-sa \
  --attach-policy-arn arn:aws:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy \
  --approve --role-only \
  --role-name AmazonEKS_EBS_CSI_DriverRole

aws eks update-addon \
  --cluster-name healthcare-platform --addon-name aws-ebs-csi-driver \
  --region "$AWS_REGION" \
  --service-account-role-arn "arn:aws:iam::${AWS_ACCOUNT_ID}:role/AmazonEKS_EBS_CSI_DriverRole"
```

Verify:

```bash
aws eks list-addons --cluster-name healthcare-platform --region "$AWS_REGION"
aws eks describe-addon --cluster-name healthcare-platform --addon-name aws-ebs-csi-driver \
  --region "$AWS_REGION" --query 'addon.status'
```

---

# Step 17 — Confirm IAM OIDC

EKS workloads should not use static AWS access keys. The preferred architecture is:

```text
Pod
 │
 ▼
Kubernetes ServiceAccount
 │
 ▼
OIDC
 │
 ▼
IAM Role
 │
 ▼
AWS Service
```

If the cluster was created with `iam.withOIDC: true` in Step 12, this is already done. Confirm:

```bash
aws eks describe-cluster --name healthcare-platform --region "$AWS_REGION" \
  --query 'cluster.identity.oidc.issuer' --output text

aws iam list-open-id-connect-providers --query 'OpenIDConnectProviderList[].Arn' --output text
```

If you created the cluster a different way and no OIDC provider is listed:

```bash
eksctl utils associate-iam-oidc-provider \
  --cluster healthcare-platform --region "$AWS_REGION" --approve
```

The OIDC provider will be used for the IRSA roles created in Steps 20 (S3) and 26 (Secrets
Manager) below.

---

# Step 18 — Create RDS PostgreSQL

First, allow the EKS node security group to reach RDS on 5432 — the EKS node SG now exists
(created automatically by `eksctl` in Step 13), so look it up and authorize it:

```bash
export SG_EKS_NODES_ID=$(aws ec2 describe-security-groups \
  --filters "Name=tag:aws:eks:cluster-name,Values=healthcare-platform" \
            "Name=group-name,Values=*ClusterSharedNodeSecurityGroup*" \
  --query 'SecurityGroups[0].GroupId' --output text)

aws ec2 authorize-security-group-ingress \
  --group-id "$SG_RDS_ID" --protocol tcp --port 5432 \
  --source-group "$SG_EKS_NODES_ID"
```

Create a DB subnet group spanning both private subnets, then the instance itself:

```bash
aws rds create-db-subnet-group \
  --db-subnet-group-name healthcare-platform-db-subnets \
  --db-subnet-group-description "Private subnets for RDS" \
  --subnet-ids "$SUBNET_PRIVATE_A" "$SUBNET_PRIVATE_B"

export RDS_MASTER_PASSWORD=$(openssl rand -base64 24)
echo "Save this — it will be stored in Secrets Manager in Step 25: $RDS_MASTER_PASSWORD"

aws rds create-db-instance \
  --db-instance-identifier healthcare-platform-db \
  --engine postgres \
  --engine-version 16 \
  --db-instance-class db.t3.micro \
  --allocated-storage 20 \
  --master-username hcplatform_admin \
  --master-user-password "$RDS_MASTER_PASSWORD" \
  --vpc-security-group-ids "$SG_RDS_ID" \
  --db-subnet-group-name healthcare-platform-db-subnets \
  --no-publicly-accessible \
  --backup-retention-period 7 \
  --storage-encrypted

aws rds wait db-instance-available --db-instance-identifier healthcare-platform-db

export RDS_ENDPOINT=$(aws rds describe-db-instances \
  --db-instance-identifier healthcare-platform-db \
  --query 'DBInstances[0].Endpoint.Address' --output text)
echo "$RDS_ENDPOINT"
```

`db.t3.micro` / 20 GB matches this repo's "development-appropriate instance size" language —
size up for a real environment.

---

# Step 19 — Create the Microservice Databases

The application uses a database-per-service model — the same 9 databases docker-compose creates
locally (see `POSTGRES_MULTIPLE_DATABASES` in the repo's `docker-compose.yml`), created here on
the shared RDS instance instead:

```text
identity_db, patient_db, doctor_db, appointment_db, emr_db,
billing_db, notification_db, audit_db, analytics_db
```

Connect as the master user and create one database + one least-privilege user per service:

```bash
for svc in identity patient doctor appointment emr billing notification audit analytics; do
  db="${svc}_db"
  user="${svc}_user"
  pass=$(openssl rand -base64 24)
  echo "${svc}: password=${pass}"   # capture these for Step 25's Secrets Manager entries

  PGPASSWORD="$RDS_MASTER_PASSWORD" psql -h "$RDS_ENDPOINT" -U hcplatform_admin -d postgres <<SQL
CREATE DATABASE ${db};
CREATE USER ${user} WITH PASSWORD '${pass}';
GRANT ALL PRIVILEGES ON DATABASE ${db} TO ${user};
SQL
done
```

Conceptually:

```text
RDS PostgreSQL
│
├── identity_db   (identity_user)
├── patient_db    (patient_user)
├── doctor_db     (doctor_user)
├── appointment_db (appointment_user)
├── emr_db        (emr_user)
├── billing_db    (billing_user)
├── notification_db (notification_user)
├── audit_db      (audit_user)
└── analytics_db  (analytics_user)
```

Each service has its own database and its own credentials — no shared PostgreSQL user, matching
this platform's database-per-service boundary.

---

# Step 20 — Create ElastiCache Redis

Authorize the EKS node security group on 6379, same pattern as RDS:

```bash
aws ec2 authorize-security-group-ingress \
  --group-id "$SG_REDIS_ID" --protocol tcp --port 6379 \
  --source-group "$SG_EKS_NODES_ID"

aws elasticache create-cache-subnet-group \
  --cache-subnet-group-name healthcare-platform-redis-subnets \
  --cache-subnet-group-description "Private subnets for Redis" \
  --subnet-ids "$SUBNET_PRIVATE_A" "$SUBNET_PRIVATE_B"

aws elasticache create-cache-cluster \
  --cache-cluster-id healthcare-platform-redis \
  --engine redis \
  --engine-version 7.1 \
  --cache-node-type cache.t3.micro \
  --num-cache-nodes 1 \
  --cache-subnet-group-name healthcare-platform-redis-subnets \
  --security-group-ids "$SG_REDIS_ID"

aws elasticache wait cache-cluster-available --cache-cluster-id healthcare-platform-redis

export REDIS_ENDPOINT=$(aws elasticache describe-cache-clusters \
  --cache-cluster-id healthcare-platform-redis --show-cache-node-info \
  --query 'CacheClusters[0].CacheNodes[0].Endpoint.Address' --output text)
echo "$REDIS_ENDPOINT"
```

The architecture becomes:

```text
EKS
 │
 │ TCP 6379
 ▼
ElastiCache Redis
```

Do not expose Redis publicly. For production, use `create-replication-group` instead of a single
`create-cache-cluster` node, for automatic failover.

---

# Step 21 — Choose Kafka Deployment

AWS supports two options.

## Option A — Amazon MSK

Recommended when you want AWS to manage the Kafka infrastructure. See Step 22.

```text
EKS
 │
 ▼
Amazon MSK
 │
 └── Kafka topics
```

## Option B — Self-hosted Kafka

Deploy Kafka inside EKS, same as the platform's own local dev/Minikube setup — see
`docker-compose.yml`'s `kafka`/`zookeeper` services for the equivalent container config, and
Step 29 below for how the in-cluster RabbitMQ/Elasticsearch are deployed the same way.

```text
EKS
│
├── Kafka
├── RabbitMQ
└── Elasticsearch
```

This is closer to the current Kubernetes development environment.

If choosing this option, skip Step 22 and instead deploy Kafka via Helm/manifests the same way
Step 29 deploys RabbitMQ, then set `KAFKA_BOOTSTRAP_SERVERS` in Step 36 to the in-cluster
Kafka Service DNS name instead of an MSK endpoint.

---

# Step 22 — Create MSK

If using MSK, authorize the EKS node security group first:

```bash
aws ec2 authorize-security-group-ingress \
  --group-id "$SG_MSK_ID" --protocol tcp --port 9092 \
  --source-group "$SG_EKS_NODES_ID"
# Also open 9096 instead/as well if using SASL_SCRAM or TLS client auth.
```

MSK cluster creation takes a broker-node configuration object, so it's given as a JSON file
rather than flat CLI flags:

```bash
cat > msk-broker-config.json <<EOF
{
  "InstanceType": "kafka.m5.large",
  "ClientSubnets": ["${SUBNET_PRIVATE_A}", "${SUBNET_PRIVATE_B}"],
  "SecurityGroups": ["${SG_MSK_ID}"],
  "StorageInfo": { "EbsStorageInfo": { "VolumeSize": 100 } }
}
EOF

aws kafka create-cluster \
  --cluster-name healthcare-platform-msk \
  --kafka-version "3.6.0" \
  --number-of-broker-nodes 2 \
  --broker-node-group-info file://msk-broker-config.json \
  --encryption-info '{"EncryptionInTransit":{"ClientBroker":"TLS","InCluster":true}}'
```

This takes 20–30 minutes to provision. Poll status:

```bash
export MSK_CLUSTER_ARN=$(aws kafka list-clusters \
  --cluster-name-filter healthcare-platform-msk \
  --query 'ClusterInfoList[0].ClusterArn' --output text)

aws kafka describe-cluster --cluster-arn "$MSK_CLUSTER_ARN" --query 'ClusterInfo.State'
```

Once `ACTIVE`, get the bootstrap brokers for Step 36:

```bash
aws kafka get-bootstrap-brokers --cluster-arn "$MSK_CLUSTER_ARN" \
  --query 'BootstrapBrokerStringTls' --output text
```

For production, prefer SASL/SCRAM or mTLS client authentication over the TLS-in-transit-only
config above — see the AWS MSK authentication documentation for the exact `--client-authentication`
flag shape, since it depends on whether you're using an ACM Private CA or IAM-based auth.

---

# Step 23 — Create S3 Bucket

Bucket names are globally unique across all of AWS — pick your own suffix:

```bash
export S3_BUCKET="healthcare-platform-emr-attachments-${AWS_ACCOUNT_ID}"

aws s3api create-bucket \
  --bucket "$S3_BUCKET" \
  --region "$AWS_REGION" \
  --create-bucket-configuration LocationConstraint="$AWS_REGION"

aws s3api put-public-access-block --bucket "$S3_BUCKET" \
  --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true

aws s3api put-bucket-versioning --bucket "$S3_BUCKET" \
  --versioning-configuration Status=Enabled

aws s3api put-bucket-encryption --bucket "$S3_BUCKET" \
  --server-side-encryption-configuration '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
```

This bucket stores EMR attachments. Do not make it public.

---

# Step 24 — Create S3 IAM Policy and IRSA Role

Create an IAM policy allowing only the required S3 operations, scoped to this one bucket:

```bash
cat > emr-s3-policy.json <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"],
      "Resource": "arn:aws:s3:::${S3_BUCKET}/*"
    },
    {
      "Effect": "Allow",
      "Action": ["s3:ListBucket"],
      "Resource": "arn:aws:s3:::${S3_BUCKET}"
    }
  ]
}
EOF

aws iam create-policy \
  --policy-name healthcare-platform-emr-s3-policy \
  --policy-document file://emr-s3-policy.json
```

Bind it to a Kubernetes ServiceAccount via IRSA, so only `emr-service` pods can assume it — no
other service gets S3 access:

```bash
eksctl create iamserviceaccount \
  --cluster healthcare-platform --region "$AWS_REGION" \
  --namespace healthcare-platform --name emr-service \
  --attach-policy-arn "arn:aws:iam::${AWS_ACCOUNT_ID}:policy/healthcare-platform-emr-s3-policy" \
  --approve
```

The Helm values in Step 21 (deploy) need `emr-service`'s pod spec to use this same
ServiceAccount name (`emr-service`) so the pod actually picks up the role.

---

# Step 25 — Create Secrets Manager Secrets

Create one secret per service, using the real database credentials generated in Step 19 (you
captured each service's password in that loop's output) and the RDS/Redis endpoints from
Steps 18/20:

```bash
for svc in identity patient doctor appointment emr billing notification audit analytics; do
  # Replace <password-for-$svc> with the value that step printed for this service.
  aws secretsmanager create-secret \
    --name "healthcare/${svc}/database" \
    --secret-string "{\"host\":\"${RDS_ENDPOINT}\",\"port\":5432,\"database\":\"${svc}_db\",\"username\":\"${svc}_user\",\"password\":\"<password-for-${svc}>\"}"
done

aws secretsmanager create-secret \
  --name healthcare/redis \
  --secret-string "{\"host\":\"${REDIS_ENDPOINT}\",\"port\":6379}"

# Kafka: use the bootstrap-brokers string from Step 22 (MSK) or the in-cluster Service DNS (self-hosted)
aws secretsmanager create-secret \
  --name healthcare/kafka \
  --secret-string '{"bootstrapServers":"<from-step-22-or-in-cluster-dns>"}'

# RS256 signing keypair for identity-service (only it consumes these; every other service
# verifies via the public JWKS endpoint).
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out /tmp/jwt_priv.pem
openssl pkey -in /tmp/jwt_priv.pem -pubout -out /tmp/jwt_pub.pem
aws secretsmanager create-secret \
  --name healthcare/jwt \
  --secret-string "{\"privateKey\":\"$(base64 -w0 < /tmp/jwt_priv.pem)\",\"publicKey\":\"$(base64 -w0 < /tmp/jwt_pub.pem)\"}"
rm -f /tmp/jwt_priv.pem /tmp/jwt_pub.pem
```

Each secret contains only the credentials/configuration the corresponding service actually
reads (matching the per-service `DB_HOST`/`DB_USER`/`DB_PASSWORD` and — for identity-service —
`JWT_RSA_PRIVATE_KEY`/`JWT_RSA_PUBLIC_KEY` environment variables the Spring Boot services
already expect — see each service's `application.yml`).

---

# Step 26 — Give Pods Access to Secrets

Do not place AWS access keys inside Kubernetes Secrets. Use IRSA, the same pattern as Step 24:

```text
Spring Boot Pod
      │
      ▼
ServiceAccount
      │
      ▼
IAM Role
      │
      ▼
Secrets Manager
```

Create one IAM policy covering all the `healthcare/*` secrets, and one ServiceAccount per
service so each pod can only read its own secret:

```bash
cat > secrets-read-policy.json <<EOF
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["secretsmanager:GetSecretValue"],
    "Resource": "arn:aws:secretsmanager:${AWS_REGION}:${AWS_ACCOUNT_ID}:secret:healthcare/*"
  }]
}
EOF

aws iam create-policy \
  --policy-name healthcare-platform-secrets-read-policy \
  --policy-document file://secrets-read-policy.json

for svc in identity-service patient-service doctor-service appointment-service \
           emr-service billing-service notification-service audit-service \
           analytics-service graphql-gateway; do
  eksctl create iamserviceaccount \
    --cluster healthcare-platform --region "$AWS_REGION" \
    --namespace healthcare-platform --name "$svc" \
    --attach-policy-arn "arn:aws:iam::${AWS_ACCOUNT_ID}:policy/healthcare-platform-secrets-read-policy" \
    --approve
done
```

This allows each application pod to retrieve its own secret without storing AWS credentials
inside the container image or a plain Kubernetes Secret.

---

# Step 27 — Install AWS Load Balancer Controller

The application needs an AWS Application Load Balancer, created from the cluster's Ingress
resource:

```text
Internet
   │
   ▼
ALB
   │
   ▼
Kubernetes Ingress
   │
   ▼
GraphQL Gateway / Frontend
```

The controller itself needs an IRSA role with the AWS-published policy for it:

```bash
curl -o alb-iam-policy.json \
  https://raw.githubusercontent.com/kubernetes-sigs/aws-load-balancer-controller/main/docs/install/iam_policy.json

aws iam create-policy \
  --policy-name AWSLoadBalancerControllerIAMPolicy \
  --policy-document file://alb-iam-policy.json

eksctl create iamserviceaccount \
  --cluster healthcare-platform --region "$AWS_REGION" \
  --namespace kube-system --name aws-load-balancer-controller \
  --attach-policy-arn "arn:aws:iam::${AWS_ACCOUNT_ID}:policy/AWSLoadBalancerControllerIAMPolicy" \
  --approve

helm repo add eks https://aws.github.io/eks-charts
helm repo update

helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  --namespace kube-system \
  --set clusterName=healthcare-platform \
  --set serviceAccount.create=false \
  --set serviceAccount.name=aws-load-balancer-controller
```

Verify:

```bash
kubectl get deployment -n kube-system aws-load-balancer-controller
```

---

# Step 28 — Configure CloudWatch

Enable Container Insights via the CloudWatch observability EKS add-on:

```bash
aws eks create-addon \
  --cluster-name healthcare-platform \
  --addon-name amazon-cloudwatch-observability \
  --region "$AWS_REGION" \
  --resolve-conflicts OVERWRITE
```

This collects:

```text
Container logs
Node metrics
Pod metrics
Cluster metrics
```

The target architecture is:

```text
EKS
 │
 ▼
CloudWatch
 │
 ├── Logs
 ├── Metrics
 └── Container Insights
```

View collected metrics:

```bash
aws cloudwatch list-metrics --namespace ContainerInsights \
  --dimensions Name=ClusterName,Value=healthcare-platform
```

Application-level Prometheus/Grafana (already used in this platform's local/dev environment,
see `docker-compose.yml`) can still be deployed separately inside EKS if required.

---

# Step 29 — Deploy RabbitMQ

RabbitMQ remains self-hosted inside EKS, deployed via the Bitnami Helm chart:

```bash
helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo update

kubectl create namespace healthcare-platform --dry-run=client -o yaml | kubectl apply -f -

helm install rabbitmq bitnami/rabbitmq \
  --namespace healthcare-platform \
  --set auth.username=notification_service \
  --set auth.password="$(openssl rand -base64 24)" \
  --set persistence.size=8Gi
```

Verify:

```bash
kubectl get pods -n healthcare-platform | grep rabbit
kubectl get svc -n healthcare-platform | grep rabbit
```

RabbitMQ should be reachable only from the required application services (the chart's default
ClusterIP Service already keeps it off any public endpoint).

---

# Step 30 — Deploy Elasticsearch

Elasticsearch also remains inside EKS:

```bash
helm repo add elastic https://helm.elastic.co
helm repo update

helm install elasticsearch elastic/elasticsearch \
  --namespace healthcare-platform \
  --set replicas=1 \
  --set minimumMasterNodes=1 \
  --set resources.requests.memory=1Gi \
  --set resources.limits.memory=2Gi
```

Verify:

```bash
kubectl get pods -n healthcare-platform | grep elasticsearch
kubectl get svc -n healthcare-platform | grep elasticsearch
```

---

# Step 31 — Build the Application Images

Build every service's image from the repository root:

```text
identity-service, patient-service, doctor-service, appointment-service, emr-service,
billing-service, notification-service, audit-service, analytics-service, graphql-gateway,
frontend
```

```bash
for svc in identity-service patient-service doctor-service appointment-service \
           emr-service billing-service notification-service audit-service \
           analytics-service graphql-gateway; do
  docker build -t "$svc:latest" -f "backend/${svc}/Dockerfile" backend
done

docker build -t frontend:latest frontend
```

(Backend services build with `backend` as the Docker context, not the per-service directory —
matching how `docker-compose.yml` builds them, since the multi-module Maven build needs the
sibling `common`/`grpc-contracts` modules on the build context.)

---

# Step 32 — Authenticate Docker to ECR

```bash
aws ecr get-login-password --region "$AWS_REGION" \
| docker login --username AWS --password-stdin \
  "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
```

---

# Step 33 — Tag Images

```bash
for svc in identity-service patient-service doctor-service appointment-service \
           emr-service billing-service notification-service audit-service \
           analytics-service graphql-gateway frontend; do
  docker tag "$svc:latest" \
    "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${svc}:latest"
done
```

---

# Step 34 — Push Images

```bash
for svc in identity-service patient-service doctor-service appointment-service \
           emr-service billing-service notification-service audit-service \
           analytics-service graphql-gateway frontend; do
  docker push "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${svc}:latest"
done
```

Verify:

```bash
aws ecr describe-images --repository-name patient-service --region "$AWS_REGION" \
  --query 'imageDetails[].imageTags' --output table
```

---

# Step 35 — Deploy the Application

Once AWS infrastructure is ready, deploy the application using:

```text
infra/helm
```

or:

```text
infra/k8s
```

> **Note on this repo's current state:** `infra/helm/healthcare-platform` exists but is a
> minimal scaffold today (`Chart.yaml`, `values.yaml`, one `templates/service.yaml`) — it is
> not yet a complete set of per-service Deployment/Service/Ingress templates. `infra/k8s/base`
> is the more complete, working manifest set, but its overlays currently cover `dev` (Minikube)
> and `aks` (Azure) only — there is no `aws` overlay yet. Either fill in the Helm chart's
> per-service templates, or add an `infra/k8s/overlays/aws` Kustomize overlay (patching image
> registries to ECR and adding the IRSA ServiceAccount annotations from Steps 24/26), before
> this step is actually runnable end-to-end.

The application deployment should consume the AWS infrastructure values:

```text
EKS
 ├── ECR image URLs
 ├── RDS endpoint
 ├── Redis endpoint
 ├── MSK bootstrap servers
 ├── S3 bucket
 └── Secrets Manager configuration
```

---

# Step 36 — Configure Application Environment

The Spring Boot services need configuration for:

### PostgreSQL

```text
RDS_ENDPOINT   (from Step 18)
Database name  (<service>_db, from Step 19)
Username       (<service>_user, from Step 19)
Password       (from Secrets Manager, Step 25)
Port           5432
```

### Redis

```text
REDIS_ENDPOINT   (from Step 20)
Port             6379
```

### Kafka

If using MSK:

```text
Bootstrap brokers (from Step 22)
TLS / SASL configuration matching the --client-authentication chosen there
```

If using self-hosted Kafka:

```text
In-cluster Kafka Service DNS name (e.g. kafka.healthcare-platform.svc.cluster.local:9092)
```

### S3

```text
S3_BUCKET   (from Step 23)
AWS_REGION
IRSA identity (emr-service ServiceAccount, from Step 24)
```

### Secrets Manager

```text
Secret names from Step 25 (healthcare/<service>/database, healthcare/redis, healthcare/kafka, healthcare/jwt)
```

---

# Step 37 — Deploy Helm Charts

From the repository:

```bash
cd infra/helm
```

The conceptual command, once the AWS-specific values file exists (see the note in Step 35):

```bash
helm upgrade --install healthcare-platform ./healthcare-platform \
  --namespace healthcare-platform \
  --create-namespace \
  -f values.yaml \
  -f values-aws.yaml \
  --set global.imageRegistry="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com" \
  --set global.imageTag=latest
```

Deploy the infrastructure dependencies first (already done in Steps 29–30):

```text
RabbitMQ
Elasticsearch
```

Then:

```text
Application services
```

Then:

```text
GraphQL Gateway
Frontend
```

---

# Step 38 — Verify Kubernetes

```bash
kubectl get nodes
kubectl get namespaces
kubectl get pods -n healthcare-platform
kubectl get svc -n healthcare-platform
kubectl get ingress -n healthcare-platform
```

All application pods should eventually reach `Running`:

```bash
kubectl get pods -n healthcare-platform -w
```

---

# Step 39 — Verify Database Connectivity

```bash
kubectl logs -n healthcare-platform deployment/patient-service | grep -i "hikaripool\|flyway"
```

Look for successful PostgreSQL connections (HikariCP pool startup, Flyway migration success) and
verify each service connects to its own database:

```text
Patient Service      → patient_db
Doctor Service        → doctor_db
Appointment Service   → appointment_db
```

---

# Step 40 — Verify Redis

```bash
kubectl logs -n healthcare-platform deployment/patient-service | grep -i redis
```

Look for Redis connection errors (there shouldn't be any) — `patient-service` and
`doctor-service` are the two services that actually cache through Redis in this platform.

---

# Step 41 — Verify Kafka

Trigger a real workflow that publishes an event, e.g. booking an appointment through the
frontend or `POST /api/v1/appointments`, then check the consuming services picked it up:

```text
appointment-service (producer)
   │
   ▼
appointment.events topic
   │
   ▼
audit-service, analytics-service, notification-service (consumers)
```

```bash
kubectl logs -n healthcare-platform deployment/audit-service | grep -i "AppointmentCreatedEvent"
```

---

# Step 42 — Verify RabbitMQ

```text
notification-service (producer)
   │
   ▼
RabbitMQ
   │
   ▼
email/SMS worker (consumer)
```

```bash
kubectl get pods -n healthcare-platform | grep rabbit
kubectl logs -n healthcare-platform deployment/notification-service | grep -i rabbit
```

---

# Step 43 — Verify S3

Trigger an EMR attachment upload through the application, then confirm the object landed:

```text
EMR Service
     │
     ▼
IAM / IRSA (Step 24)
     │
     ▼
S3
     │
     ▼
EMR attachment
```

```bash
aws s3 ls "s3://${S3_BUCKET}/"
```

---

# Step 44 — Verify Secrets Manager

Verify that the application can retrieve its required secrets. Do not print secret values —
instead, verify from application logs that startup/authentication succeeds:

```bash
kubectl logs -n healthcare-platform deployment/identity-service | grep -i "started\|error"
```

A service that failed to read its secret will typically fail to start at all (missing
`DB_PASSWORD`, or `JWT_RSA_PRIVATE_KEY` for identity-service), which shows up as a
`CrashLoopBackOff`:

```bash
kubectl get pods -n healthcare-platform | grep -v Running
```

---

# Step 45 — Configure Route 53

Once the ALB exists, retrieve its DNS name:

```bash
export ALB_HOSTNAME=$(kubectl get ingress -n healthcare-platform platform-gateway \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')
echo "$ALB_HOSTNAME"
```

You should see an address similar to:

```text
xxxx.us-east-1.elb.amazonaws.com
```

Create the Route 53 record (replace `<your-hosted-zone-id>` and `<your-domain>` with your own):

```bash
export ALB_ZONE_ID=$(aws elbv2 describe-load-balancers \
  --query "LoadBalancers[?DNSName=='${ALB_HOSTNAME}'].CanonicalHostedZoneId" --output text)

cat > route53-change.json <<EOF
{
  "Changes": [{
    "Action": "UPSERT",
    "ResourceRecordSet": {
      "Name": "api.<your-domain>",
      "Type": "A",
      "AliasTarget": {
        "HostedZoneId": "${ALB_ZONE_ID}",
        "DNSName": "${ALB_HOSTNAME}",
        "EvaluateTargetHealth": true
      }
    }
  }]
}
EOF

aws route53 change-resource-record-sets \
  --hosted-zone-id "<your-hosted-zone-id>" \
  --change-batch file://route53-change.json
```

For the frontend, repeat with `app.<your-domain>` pointing at the same (or a separate) ALB,
depending on how the Ingress in Step 35/37 routes the two.

---

# Final Architecture

After completing the deployment, the environment should look like:

```text
                              Internet
                                  │
                                  ▼
                              Route 53
                                  │
                                  ▼
                         Application Load Balancer
                                  │
                                  ▼
                                 EKS
                                  │
        ┌─────────────────────────┼─────────────────────────┐
        │                         │                         │
        ▼                         ▼                         ▼
    Frontend                GraphQL Gateway          Spring Boot
                                                        Services
                                                            │
                          ┌─────────────────────────────────┼───────────────┐
                          │                                 │               │
                          ▼                                 ▼               ▼
                     RDS PostgreSQL                   ElastiCache        Kafka
                                                         Redis             │
                                                                         MSK
                                                                          │
                          ┌───────────────────────────────────────────────┘
                          │
                          ▼
                         S3

                     Secrets Manager
                          ▲
                          │
                         EKS

                     CloudWatch
                          ▲
                          │
                         EKS

                     In-cluster:
                     ├── RabbitMQ
                     └── Elasticsearch
```

---

# Final Checklist

Before considering the AWS deployment complete:

## AWS Infrastructure

```text
[ ] AWS account configured
[ ] Region selected
[ ] VPC created
[ ] 2 public subnets created
[ ] 2 private subnets created
[ ] Internet Gateway created
[ ] NAT Gateway created
[ ] Route tables configured
[ ] Security groups configured
```

## EKS

```text
[ ] EKS cluster created
[ ] System node group created
[ ] Application node group created
[ ] kubectl configured
[ ] Nodes are Ready
[ ] VPC CNI installed
[ ] CoreDNS installed
[ ] kube-proxy installed
[ ] EBS CSI Driver installed
[ ] OIDC configured
```

## AWS Services

```text
[ ] ECR repositories created
[ ] RDS PostgreSQL created
[ ] Per-service databases created
[ ] Per-service DB users created
[ ] ElastiCache Redis created
[ ] MSK created OR Kafka deployed in EKS
[ ] S3 bucket created
[ ] Secrets Manager secrets created
```

## Kubernetes Infrastructure

```text
[ ] AWS Load Balancer Controller installed
[ ] RabbitMQ deployed
[ ] Elasticsearch deployed
[ ] CloudWatch/Container Insights configured
```

## Application

```text
[ ] Docker images built
[ ] Images pushed to ECR
[ ] Application secrets configured
[ ] Spring Boot services deployed
[ ] GraphQL Gateway deployed
[ ] Angular frontend deployed
[ ] Ingress created
[ ] ALB created
[ ] Route 53 configured
```

## Verification

```text
[ ] All nodes Ready
[ ] All pods Running
[ ] PostgreSQL connections working
[ ] Redis connections working
[ ] Kafka working
[ ] RabbitMQ working
[ ] Elasticsearch working
[ ] S3 uploads working
[ ] Secrets loading successfully
[ ] GraphQL API accessible
[ ] Frontend accessible
[ ] CloudWatch logs visible
```

---

# Manual Deployment vs Terraform

The manual deployment described here creates the same logical architecture as an
`infra/terraform/aws/` module would (not yet written in this repo — see
[`docs/cloud/aws/README.md`](README.md) for the target architecture and `VISION.md`'s status
table, which marks AWS as "designed, not deployed"). `infra/terraform/` today only has an Azure
environment (`envs/dev`) and the state-bootstrap module.

The difference is only how the infrastructure is created:

```text
Manual
──────

AWS Console / AWS CLI / eksctl
        │
        ▼
AWS Resources
        │
        ▼
EKS
        │
        ▼
Helm


Terraform
─────────

Terraform
        │
        ▼
AWS API
        │
        ▼
AWS Resources
        │
        ▼
EKS
        │
        ▼
Helm
```

Manual deployment is useful for understanding the infrastructure.

Terraform is useful for making that infrastructure:

* repeatable
* version-controlled
* reviewable
* reproducible
* easier to destroy/recreate
* consistent across environments

The recommended workflow is therefore:

```text
Understand manually
        ↓
Deploy manually
        ↓
Verify the architecture
        ↓
Automate with Terraform
        ↓
Use Terraform for repeatable environments
```

---

# Tearing Down

Manual resources should be destroyed in roughly the reverse order they were created, since
several depend on each other (EKS node groups before the cluster, NAT gateway before its Elastic
IP, etc.):

```bash
helm uninstall healthcare-platform -n healthcare-platform
helm uninstall elasticsearch -n healthcare-platform
helm uninstall rabbitmq -n healthcare-platform
helm uninstall aws-load-balancer-controller -n kube-system

aws kafka delete-cluster --cluster-arn "$MSK_CLUSTER_ARN"          # if MSK was used
aws elasticache delete-cache-cluster --cache-cluster-id healthcare-platform-redis
aws rds delete-db-instance --db-instance-identifier healthcare-platform-db --skip-final-snapshot

eksctl delete nodegroup --cluster healthcare-platform --name apps
eksctl delete nodegroup --cluster healthcare-platform --name system
eksctl delete cluster --name healthcare-platform --region "$AWS_REGION"

aws ec2 delete-nat-gateway --nat-gateway-id "$NAT_GW_ID"
# wait for the NAT gateway to finish deleting before releasing its EIP
aws ec2 release-address --allocation-id "$EIP_ALLOC_ID"

aws s3 rm "s3://${S3_BUCKET}" --recursive
aws s3api delete-bucket --bucket "$S3_BUCKET"

for name in identity patient doctor appointment emr billing notification audit analytics \
            redis kafka jwt; do
  aws secretsmanager delete-secret --secret-id "healthcare/${name}" --force-delete-without-recovery 2>/dev/null
  aws secretsmanager delete-secret --secret-id "healthcare/${name}/database" --force-delete-without-recovery 2>/dev/null
done

aws ecr describe-repositories --query 'repositories[].repositoryName' --output text | \
  tr '\t' '\n' | while read -r repo; do
    [[ "$repo" == healthcare* || "$repo" == *-service || "$repo" == frontend || "$repo" == graphql-gateway ]] && \
      aws ecr delete-repository --repository-name "$repo" --force
  done

aws ec2 delete-vpc --vpc-id "$VPC_ID"   # only succeeds once subnets/route-tables/IGW are detached/deleted
```

Double-check nothing billable is left running:

```bash
aws rds describe-db-instances --query 'DBInstances[].DBInstanceIdentifier'
aws elasticache describe-cache-clusters --query 'CacheClusters[].CacheClusterId'
aws eks list-clusters
aws ec2 describe-nat-gateways --filter Name=state,Values=available --query 'NatGateways[].NatGatewayId'
```
