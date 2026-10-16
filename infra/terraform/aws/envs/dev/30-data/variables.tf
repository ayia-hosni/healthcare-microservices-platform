variable "project" {
  type    = string
  default = "healthcare-platform"
}

variable "environment" {
  type    = string
  default = "dev"
}

variable "region" {
  type    = string
  default = "us-east-1"
}

variable "tags" {
  type = map(string)
  default = {
    project   = "healthcare-platform"
    managedby = "terraform"
  }
}

variable "tf_state_bucket" {
  type    = string
  default = "healthcare-platform-tfstate"
}

variable "tf_state_region" {
  type    = string
  default = "us-east-1"
}

# --- RDS ---
variable "postgres_version" {
  type    = string
  default = "16"
}

variable "db_instance_class" {
  type    = string
  default = "db.t4g.micro"
}

variable "db_allocated_storage" {
  type    = number
  default = 20
}

variable "db_max_allocated_storage" {
  type    = number
  default = 100
}

variable "db_master_username" {
  type    = string
  default = "pgadmin"
}

variable "db_backup_retention_days" {
  type    = number
  default = 7
}

variable "db_multi_az" {
  type    = bool
  default = false
}

variable "db_deletion_protection" {
  description = "true blocks `terraform destroy` from dropping the database — flip to false before an intentional teardown."
  type        = bool
  default     = false
}

variable "db_publicly_accessible" {
  description = "true = public endpoint locked to admin CIDRs (lets apply run the postgresql provider from your workstation). false = VPC-private (apply from inside the VPC). See README."
  type        = bool
  default     = true
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
variable "redis_engine_version" {
  type    = string
  default = "7.1"
}

variable "redis_node_type" {
  type    = string
  default = "cache.t4g.micro"
}

# --- Kafka / MSK ---
variable "kafka_mode" {
  description = "\"self-hosted\" (default, provisions nothing) or \"msk\". Must match 20-platform's value."
  type        = string
  default     = "self-hosted"
  validation {
    condition     = contains(["self-hosted", "msk"], var.kafka_mode)
    error_message = "kafka_mode must be \"self-hosted\" or \"msk\"."
  }
}

variable "msk_kafka_version" {
  type    = string
  default = "3.6.0"
}

variable "msk_broker_instance_type" {
  type    = string
  default = "kafka.t3.small"
}

variable "msk_broker_count" {
  type    = number
  default = 2
}

variable "msk_broker_ebs_gb" {
  type    = number
  default = 20
}

variable "cloudwatch_log_retention_days" {
  type    = number
  default = 30
}

# --- S3 ---
variable "emr_attachments_bucket_name" {
  description = "Empty = \"<project>-<env>-emr-attachments-<account-id>\". If you override this, update 20-platform's emr_bucket_arn local to match."
  type        = string
  default     = ""
}
