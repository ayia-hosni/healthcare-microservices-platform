resource "azurerm_key_vault" "main" {
  name                = "kv-${substr(replace(local.name_prefix, "-", ""), 0, 17)}" # 24 char max, globally unique
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
  tenant_id           = data.azurerm_client_config.current.tenant_id
  sku_name            = "standard"

  # PHI-adjacent secrets live here (DB/Redis/Storage/Event Hubs credentials) — protect against
  # both accidental and malicious deletion.
  soft_delete_retention_days = 90
  purge_protection_enabled   = true

  rbac_authorization_enabled = true

  network_acls {
    default_action = "Deny"
    bypass         = "AzureServices"
    ip_rules       = var.admin_ip_cidrs
  }

  tags = local.tags
}

# The identity running `terraform apply` needs this to write the secrets defined below.
resource "azurerm_role_assignment" "keyvault_admin_for_apply_identity" {
  scope                = azurerm_key_vault.main.id
  role_definition_name = "Key Vault Administrator"
  principal_id         = data.azurerm_client_config.current.object_id
}

# In-cluster pods (via the workload identity federated in aks.tf) can only read, never manage.
resource "azurerm_role_assignment" "keyvault_reader_for_workload_identity" {
  scope                = azurerm_key_vault.main.id
  role_definition_name = "Key Vault Secrets User"
  principal_id         = azurerm_user_assigned_identity.workload.principal_id
}

resource "random_password" "jwt_secret" {
  length  = 64
  special = false # JWT secret is used as raw bytes/base64 material — avoid shell/YAML-unsafe characters
}

resource "azurerm_key_vault_secret" "jwt_secret" {
  name         = "jwt-secret"
  value        = random_password.jwt_secret.result
  key_vault_id = azurerm_key_vault.main.id
  depends_on   = [azurerm_role_assignment.keyvault_admin_for_apply_identity]
}
