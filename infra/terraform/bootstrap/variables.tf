variable "project" {
  description = "Short project name used to derive resource names."
  type        = string
  default     = "healthcare-platform"
}

variable "location" {
  description = "Azure region for the state storage account."
  type        = string
  default     = "eastus"
}

variable "storage_account_name" {
  description = <<-EOT
    Globally-unique storage account name (3-24 lowercase alphanumeric chars).
    Storage account names are a global Azure namespace, so the default here will likely
    collide — override it, e.g. "sthcptfstate<yourinitials><random4>".
  EOT
  type        = string
  default     = "sthealthplatformtfstate"
}

variable "tags" {
  description = "Tags applied to bootstrap resources."
  type        = map(string)
  default = {
    project   = "healthcare-platform"
    purpose   = "terraform-state"
    managedby = "terraform"
  }
}
