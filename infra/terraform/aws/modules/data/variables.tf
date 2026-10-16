variable "name_prefix" {
  type = string
}

variable "environment" {
  type    = string
  default = "dev"
}

variable "tags" {
  type    = map(string)
  default = {}
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "vpc_id" {
  type = string
}

variable "data_security_group_id" {
  description = "Shared SG from the network module — RDS + ElastiCache attach it."
  type        = string
}

variable "cluster_security_group_id" {
  description = "EKS cluster SG from the platform layer — allowed inbound to the data services."
  type        = string
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
  type    = bool
  default = false
}

variable "db_publicly_accessible" {
  type    = bool
  default = true
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
  type    = string
  default = "self-hosted"
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
