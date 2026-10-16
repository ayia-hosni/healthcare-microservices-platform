variable "name_prefix" {
  type = string
}

variable "tags" {
  type    = map(string)
  default = {}
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "public_subnet_ids" {
  type = list(string)
}

variable "kubernetes_version" {
  description = "null = provider default at create time (then ignore_changes'd)."
  type        = string
  default     = null
}

variable "endpoint_public_access" {
  type    = bool
  default = true
}

variable "public_access_cidrs" {
  description = "CIDRs allowed to reach the public EKS API endpoint. Ignored when endpoint_public_access = false."
  type        = list(string)
  default     = []
}

variable "system_node_instance_types" {
  type    = list(string)
  default = ["t3.medium"]
}

variable "system_node_desired_size" {
  type    = number
  default = 2
}

variable "system_node_min_size" {
  type    = number
  default = 2
}

variable "system_node_max_size" {
  type    = number
  default = 3
}

variable "app_node_instance_types" {
  type    = list(string)
  default = ["t3.large"]
}

variable "app_node_min_size" {
  description = "0 skips creating the apps node group entirely (app pods share the system group)."
  type        = number
  default     = 0
}

variable "app_node_desired_size" {
  type    = number
  default = 2
}

variable "app_node_max_size" {
  type    = number
  default = 6
}

variable "app_node_capacity_type" {
  type    = string
  default = "ON_DEMAND"
}

variable "enable_container_insights" {
  type    = bool
  default = true
}

variable "cloudwatch_log_retention_days" {
  type    = number
  default = 30
}

variable "cluster_admin_principal_arns" {
  description = "IAM principal ARNs to grant cluster-admin via EKS access entries (e.g. the Terraform caller)."
  type        = list(string)
  default     = []
}

# --- Inputs the workload IRSA policy needs, as deterministic strings (not resource refs, to
#     keep this module free of a dependency on the data/storage layers) ---
variable "workload_namespace" {
  type    = string
  default = "healthcare-platform"
}

variable "workload_service_account" {
  type    = string
  default = "platform-workload-identity"
}

variable "secretsmanager_path_prefix" {
  description = "Secrets under this path are readable by the workload role, e.g. \"healthcare-platform-dev/\"."
  type        = string
}

variable "emr_bucket_arn" {
  description = "ARN of the EMR-attachments S3 bucket (constructed deterministically by the caller)."
  type        = string
}

variable "kafka_mode" {
  type    = string
  default = "self-hosted"
}
