/*
 * Storage Account + blob container for EMR attachments — replaces MinIO.
 *
 * NOTE: emr-service uses the MinIO Java SDK (S3 API), which Azure Blob does not speak — a
 * follow-up code change to com.azure:azure-storage-blob is needed before this is usable. See
 * the tree README.
 */

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
  type = string
}

variable "admin_ip_cidrs" {
  type    = list(string)
  default = []
}

variable "container_name" {
  type    = string
  default = "emr-attachments"
}

resource "azurerm_storage_account" "main" {
  name                = substr(replace("st${var.name_prefix}", "-", ""), 0, 24)
  resource_group_name = var.resource_group_name
  location            = var.location

  account_tier             = "Standard"
  account_replication_type = "LRS"
  min_tls_version          = "TLS1_2"

  blob_properties {
    versioning_enabled = true
    delete_retention_policy {
      days = 30
    }
    container_delete_retention_policy {
      days = 30
    }
  }

  network_rules {
    default_action = "Deny"
    bypass         = ["AzureServices"]
    ip_rules       = concat([var.aks_egress_ip], [for c in var.admin_ip_cidrs : cidrhost(c, 0)])
  }

  tags = var.tags
}

resource "azurerm_storage_container" "emr" {
  name                  = var.container_name
  storage_account_id    = azurerm_storage_account.main.id
  container_access_type = "private"
}

output "account_name" {
  value = azurerm_storage_account.main.name
}

output "primary_access_key" {
  value     = azurerm_storage_account.main.primary_access_key
  sensitive = true
}

output "container_name" {
  value = azurerm_storage_container.emr.name
}
