variable "project" {
  type    = string
  default = "healthcare-platform"
}

variable "environment" {
  type    = string
  default = "dev"
}

variable "location" {
  description = <<-EOT
    Azure region. Defaults to centralus, not eastus: this subscription's Postgres Flexible
    Server provisioning is blocked in eastus/eastus2/westus2/westeurope/southcentralus on a
    Free Trial, but open in centralus/westus3/northeurope/uksouth.
  EOT
  type        = string
  default     = "centralus"
}

variable "tags" {
  type = map(string)
  default = {
    project   = "healthcare-platform"
    managedby = "terraform"
  }
}

variable "vnet_address_space" {
  type    = list(string)
  default = ["10.20.0.0/16"]
}

variable "aks_subnet_prefix" {
  type    = string
  default = "10.20.0.0/20"
}
