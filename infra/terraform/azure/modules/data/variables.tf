variable "name_prefix" {
  type = string
}

variable "resource_group_name" {
  type = string
}

variable "location" {
  type = string
}

variable "tags" {
  type    = map(string)
  default = {}
}

variable "aks_egress_ip" {
  description = "NAT Gateway public IP from the network layer — allow-listed on every data service firewall."
  type        = string
}

variable "admin_ip_cidrs" {
  description = "Your direct-access IPs (/32) for `terraform apply` / psql. Assumed /32."
  type        = list(string)
  default     = []
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

# --- Event Hubs (Kafka-compatible endpoint) ---
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
