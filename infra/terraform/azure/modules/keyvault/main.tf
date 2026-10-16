/*
 * Key Vault + RBAC. The data layer writes the actual secret values (DB/Redis/Event Hubs/
 * Storage credentials) into this vault, referencing its id from the platform layer's state.
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

variable "tenant_id" {
  type = string
}

variable "admin_object_id" {
  description = "Object id of the principal running `terraform apply` — granted Key Vault Administrator."
  type        = string
}

variable "workload_identity_principal_id" {
  description = "Principal id of the AKS workload identity — granted Key Vault Secrets User (read only)."
  type        = string
}

variable "admin_ip_cidrs" {
  type    = list(string)
  default = []
}

resource "azurerm_key_vault" "main" {
  name                = "kv-${substr(replace(var.name_prefix, "-", ""), 0, 17)}" # 24 char max, globally unique
  resource_group_name = var.resource_group_name
  location            = var.location
  tenant_id           = var.tenant_id
  sku_name            = "standard"

  soft_delete_retention_days = 90
  purge_protection_enabled   = true

  rbac_authorization_enabled = true

  network_acls {
    default_action = "Deny"
    bypass         = "AzureServices"
    ip_rules       = var.admin_ip_cidrs
  }

  tags = var.tags
}

resource "azurerm_role_assignment" "admin" {
  scope                = azurerm_key_vault.main.id
  role_definition_name = "Key Vault Administrator"
  principal_id         = var.admin_object_id
}

resource "azurerm_role_assignment" "workload_reader" {
  scope                = azurerm_key_vault.main.id
  role_definition_name = "Key Vault Secrets User"
  principal_id         = var.workload_identity_principal_id
}

output "id" {
  value = azurerm_key_vault.main.id
}

output "uri" {
  value = azurerm_key_vault.main.vault_uri
}

output "name" {
  value = azurerm_key_vault.main.name
}
