variable "project" {
  type    = string
  default = "healthcare-platform"
}

variable "environment" {
  type    = string
  default = "dev"
}

variable "tags" {
  type = map(string)
  default = {
    project   = "healthcare-platform"
    managedby = "terraform"
  }
}

variable "tf_state_resource_group" {
  type    = string
  default = "rg-healthcare-platform-tfstate"
}

variable "tf_state_storage_account" {
  type    = string
  default = "sthealthplatformtfstate"
}

variable "tf_state_container" {
  type    = string
  default = "tfstate"
}

variable "admin_ip_cidrs" {
  description = "Your direct-access IP(s) as /32 — allowed through the Postgres/Redis/Storage/Event Hubs firewalls for `terraform apply` / psql."
  type        = list(string)
}

# --- Postgres ---
variable "postgres_admin_username" {
  type    = string
  default = "pgadmin"
}

variable "postgres_sku_name" {
  type    = string
  default = "B_Standard_B2s"
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

variable "service_databases" {
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

# --- Redis ---
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

# --- Event Hubs ---
variable "eventhubs_sku" {
  type    = string
  default = "Standard"
}

variable "eventhubs_capacity" {
  type    = number
  default = 1
}

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

# --- Storage ---
variable "emr_attachments_container_name" {
  type    = string
  default = "emr-attachments"
}
