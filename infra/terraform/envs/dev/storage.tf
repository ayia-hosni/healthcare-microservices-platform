/*
 * Replaces MinIO for EMR document attachments (see
 * backend/emr-service/src/main/java/.../config/MinioConfig.java and
 * backend/emr-service/src/main/resources/application.yml, `minio.bucket: emr-attachments`).
 *
 * IMPORTANT: this alone does not complete the migration. emr-service talks to MinIO through
 * the MinIO Java SDK (io.minio.MinioClient) against an S3-style API — Azure Blob Storage does
 * not speak that API. Provisioning this account gets the infrastructure in place, but
 * emr-service's DocumentStorageClient needs a follow-up code change to the Azure Blob SDK
 * (com.azure:azure-storage-blob) before it can actually use it. See README.md.
 */

resource "azurerm_storage_account" "main" {
  # Globally unique, lowercase alphanumeric, <=24 chars.
  name                = substr(replace("st${local.name_prefix}", "-", ""), 0, 24)
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location

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
    ip_rules       = concat([azurerm_public_ip.nat_gateway.ip_address], [for c in var.admin_ip_cidrs : cidrhost(c, 0)])
  }

  tags = local.tags
}

resource "azurerm_storage_container" "emr_attachments" {
  name                  = var.emr_attachments_container_name
  storage_account_id    = azurerm_storage_account.main.id
  container_access_type = "private"
}

resource "azurerm_key_vault_secret" "storage_account_key" {
  name         = "storage-account-key"
  value        = azurerm_storage_account.main.primary_access_key
  key_vault_id = azurerm_key_vault.main.id
  depends_on   = [azurerm_role_assignment.keyvault_admin_for_apply_identity]
}
