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
  description = "Your workstation/office/VPN egress IP(s), as /32s — allowed to reach the Key Vault management endpoint (and, in 30-data, the DB firewalls)."
  type        = list(string)
}

# --- AKS ---
variable "kubernetes_version" {
  type    = string
  default = null
}

variable "aks_system_node_vm_size" {
  type    = string
  default = "Standard_B2s"
}

variable "aks_system_node_count" {
  type    = number
  default = 2
}

variable "aks_app_node_vm_size" {
  type    = string
  default = "Standard_D4s_v5"
}

variable "aks_app_node_min_count" {
  description = "0 skips the apps node pool entirely (app pods share the system pool) — POC default under the 4 vCPU quota cap."
  type        = number
  default     = 0
}

variable "aks_app_node_max_count" {
  type    = number
  default = 6
}

variable "log_analytics_retention_days" {
  type    = number
  default = 30
}
