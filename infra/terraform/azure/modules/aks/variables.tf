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

variable "subnet_id" {
  type = string
}

variable "log_analytics_workspace_id" {
  type = string
}

variable "acr_id" {
  description = "Container registry to grant the kubelet identity AcrPull on."
  type        = string
}

variable "tenant_id" {
  type = string
}

variable "cluster_admin_object_ids" {
  description = "Principal object ids to grant \"Azure Kubernetes Service RBAC Cluster Admin\" (local accounts are disabled)."
  type        = list(string)
  default     = []
}

variable "kubernetes_version" {
  type    = string
  default = null
}

variable "system_node_vm_size" {
  type    = string
  default = "Standard_B2s"
}

variable "system_node_count" {
  type    = number
  default = 2
}

variable "app_node_vm_size" {
  type    = string
  default = "Standard_D4s_v5"
}

variable "app_node_min_count" {
  description = "0 skips creating the apps node pool entirely (app pods share the system pool)."
  type        = number
  default     = 0
}

variable "app_node_max_count" {
  type    = number
  default = 6
}

variable "workload_namespace" {
  type    = string
  default = "healthcare-platform"
}

variable "workload_service_account" {
  type    = string
  default = "platform-workload-identity"
}

variable "pod_cidr" {
  type    = string
  default = "10.244.0.0/16"
}

variable "service_cidr" {
  type    = string
  default = "10.245.0.0/16"
}

variable "dns_service_ip" {
  type    = string
  default = "10.245.0.10"
}
