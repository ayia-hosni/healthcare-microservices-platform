variable "project" {
  description = "Short project name used to derive resource names."
  type        = string
  default     = "healthcare-platform"
}

variable "environment" {
  description = "Environment name (dev, staging, prod, ...). Kept as a variable even though this tree currently only stands up one environment, so it's a copy-paste starting point for the next one."
  type        = string
  default     = "dev"
}

variable "location" {
  description = "Azure region for all resources."
  type        = string
  default     = "eastus"
}

variable "admin_ip_cidrs" {
  description = <<-EOT
    CIDR blocks (your workstation/office/VPN egress, e.g. ["203.0.113.4/32"]) allowed to reach
    the management endpoints of Postgres, Redis, Storage and Event Hubs directly (e.g. for
    `terraform apply`, `psql`, or debugging). AKS reaches these services via the NAT gateway's
    outbound IP, which is allow-listed separately in network.tf/postgres.tf — this variable is
    only for *your* direct access. Required: there is no default, so a bare `apply` fails
    loudly instead of silently leaving these services open to the internet.
  EOT
  type        = list(string)
}

variable "tags" {
  description = "Tags applied to all resources."
  type        = map(string)
  default = {
    project   = "healthcare-platform"
    managedby = "terraform"
  }
}

# ---------------------------------------------------------------------------
# AKS
# ---------------------------------------------------------------------------

variable "kubernetes_version" {
  description = "AKS Kubernetes version. Leave null to track AKS's current default."
  type        = string
  default     = null
}

variable "aks_system_node_vm_size" {
  type    = string
  default = "Standard_D2s_v5"
}

variable "aks_system_node_count" {
  type    = number
  default = 3
}

variable "aks_app_node_vm_size" {
  description = "VM size for the node pool running the 9 Spring Boot services + frontend."
  type        = string
  default     = "Standard_D4s_v5"
}

variable "aks_app_node_min_count" {
  type    = number
  default = 2
}

variable "aks_app_node_max_count" {
  type    = number
  default = 6
}

# ---------------------------------------------------------------------------
# Postgres
# ---------------------------------------------------------------------------

variable "postgres_admin_username" {
  type    = string
  default = "pgadmin"
}

variable "postgres_sku_name" {
  description = "Flexible Server compute tier, e.g. B_Standard_B2s (dev/burstable) or GP_Standard_D2s_v3 (production)."
  type        = string
  default     = "B_Standard_B2s"
}

variable "postgres_version" {
  type    = string
  default = "16"
}

variable "postgres_storage_mb" {
  type    = number
  default = 32768
}

variable "postgres_backup_retention_days" {
  type    = number
  default = 7
}

variable "postgres_geo_redundant_backup" {
  type    = bool
  default = false
}

# Database-per-service — see docs/adr/0001-database-per-service.md and
# infra/docker/init-multiple-dbs.sh, which this list mirrors.
variable "service_databases" {
  description = "Microservice name -> {db_name, db_user} for the per-service Postgres databases."
  type = map(object({
    db_name = string
    db_user = string
  }))
  default = {
    identity     = { db_name = "identity_db", db_user = "identity_user" }
    patient      = { db_name = "patient_db", db_user = "patient_user" }
    doctor       = { db_name = "doctor_db", db_user = "doctor_user" }
    appointment  = { db_name = "appointment_db", db_user = "appointment_user" }
    emr          = { db_name = "emr_db", db_user = "emr_user" }
    billing      = { db_name = "billing_db", db_user = "billing_user" }
    notification = { db_name = "notification_db", db_user = "notification_user" }
    audit        = { db_name = "audit_db", db_user = "audit_user" }
    analytics    = { db_name = "analytics_db", db_user = "analytics_user" }
  }
}

# ---------------------------------------------------------------------------
# Redis
# ---------------------------------------------------------------------------

variable "redis_sku_name" {
  type    = string
  default = "Standard"
}

variable "redis_family" {
  type    = string
  default = "C"
}

variable "redis_capacity" {
  type    = number
  default = 1
}

# ---------------------------------------------------------------------------
# Event Hubs (Kafka-compatible endpoint)
# ---------------------------------------------------------------------------

variable "eventhubs_sku" {
  description = "Standard minimum required for the Kafka-compatible surface; Basic doesn't support it."
  type        = string
  default     = "Standard"
}

variable "eventhubs_capacity" {
  description = "Throughput units."
  type        = number
  default     = 1
}

# One Kafka topic == one Event Hub. Mirrors backend/common/.../events/Topics.java.
variable "kafka_topics" {
  type = list(string)
  default = [
    "patient.events",
    "doctor.events",
    "appointment.events",
    "emr.events",
    "billing.events",
    "notification.requests",
    "audit.events",
  ]
}

# ---------------------------------------------------------------------------
# Storage (Blob) — EMR attachments, replacing MinIO
# ---------------------------------------------------------------------------

variable "emr_attachments_container_name" {
  type    = string
  default = "emr-attachments"
}
