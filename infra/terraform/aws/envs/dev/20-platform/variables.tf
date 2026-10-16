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
  description = "S3 bucket holding the other layers' state (from aws/bootstrap output). Keep in sync with backend.tf."
  type        = string
  default     = "healthcare-platform-tfstate"
}

variable "tf_state_region" {
  type    = string
  default = "us-east-1"
}

# --- EKS ---
variable "kubernetes_version" {
  type    = string
  default = null
}

variable "eks_public_endpoint_enabled" {
  type    = bool
  default = true
}

variable "eks_public_access_cidrs" {
  description = "CIDRs allowed to reach the public EKS API endpoint (your egress /32s). No default — a bare apply fails loudly rather than leaving the API open to 0.0.0.0/0. Pass [] only if you also set eks_public_endpoint_enabled = false."
  type        = list(string)
}

variable "eks_system_node_instance_types" {
  type    = list(string)
  default = ["t3.medium"]
}

variable "eks_system_node_desired_size" {
  type    = number
  default = 2
}

variable "eks_system_node_min_size" {
  type    = number
  default = 2
}

variable "eks_system_node_max_size" {
  type    = number
  default = 3
}

variable "eks_app_node_instance_types" {
  type    = list(string)
  default = ["t3.large"]
}

variable "eks_app_node_min_size" {
  description = "0 skips the apps node group entirely (app pods share the system group) — POC default."
  type        = number
  default     = 0
}

variable "eks_app_node_desired_size" {
  type    = number
  default = 2
}

variable "eks_app_node_max_size" {
  type    = number
  default = 6
}

variable "eks_app_node_capacity_type" {
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

variable "kafka_mode" {
  description = "Passed to the eks module only so the workload IRSA policy knows whether to include the MSK SCRAM secret pattern. Must match 30-data's value."
  type        = string
  default     = "self-hosted"
}

# --- ECR ---
variable "service_images" {
  type = list(string)
  default = [
    "identity-service",
    "patient-service",
    "doctor-service",
    "appointment-service",
    "emr-service",
    "billing-service",
    "notification-service",
    "audit-service",
    "analytics-service",
    "graphql-gateway",
    "frontend",
  ]
}

variable "ecr_image_retention_count" {
  type    = number
  default = 15
}
