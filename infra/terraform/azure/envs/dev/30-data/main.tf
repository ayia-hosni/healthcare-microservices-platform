/*
 * Layer 30 — data. Postgres (+ per-service DBs/roles), Redis, Event Hubs, Storage, and all
 * Key Vault secret writes. Upstream: 10-network (RG, NAT egress IP) + 20-platform (Key Vault).
 * Apply after 20.
 */

locals {
  name_prefix = "${var.project}-${var.environment}"
  tags        = merge(var.tags, { environment = var.environment })
}

data "terraform_remote_state" "network" {
  backend = "azurerm"
  config = {
    resource_group_name  = var.tf_state_resource_group
    storage_account_name = var.tf_state_storage_account
    container_name       = var.tf_state_container
    key                  = "${var.project}/azure/${var.environment}/10-network.tfstate"
  }
}

data "terraform_remote_state" "platform" {
  backend = "azurerm"
  config = {
    resource_group_name  = var.tf_state_resource_group
    storage_account_name = var.tf_state_storage_account
    container_name       = var.tf_state_container
    key                  = "${var.project}/azure/${var.environment}/20-platform.tfstate"
  }
}

module "data" {
  source = "../../../modules/data"

  name_prefix         = local.name_prefix
  resource_group_name = data.terraform_remote_state.network.outputs.resource_group_name
  location            = data.terraform_remote_state.network.outputs.location
  tags                = local.tags

  aks_egress_ip  = data.terraform_remote_state.network.outputs.nat_gateway_public_ip
  admin_ip_cidrs = var.admin_ip_cidrs

  postgres_admin_username        = var.postgres_admin_username
  postgres_sku_name              = var.postgres_sku_name
  postgres_version               = var.postgres_version
  postgres_storage_mb            = var.postgres_storage_mb
  postgres_backup_retention_days = var.postgres_backup_retention_days
  postgres_geo_redundant_backup  = var.postgres_geo_redundant_backup

  redis_sku_name = var.redis_sku_name
  redis_family   = var.redis_family
  redis_capacity = var.redis_capacity

  eventhubs_sku      = var.eventhubs_sku
  eventhubs_capacity = var.eventhubs_capacity
  kafka_topics       = var.kafka_topics
}

module "storage" {
  source = "../../../modules/storage"

  name_prefix         = local.name_prefix
  resource_group_name = data.terraform_remote_state.network.outputs.resource_group_name
  location            = data.terraform_remote_state.network.outputs.location
  tags                = local.tags

  aks_egress_ip  = data.terraform_remote_state.network.outputs.nat_gateway_public_ip
  admin_ip_cidrs = var.admin_ip_cidrs
  container_name = var.emr_attachments_container_name
}

# --- Per-service databases + login roles (postgresql provider, configured in providers.tf) ---
resource "random_password" "service_db" {
  for_each = var.service_databases
  length   = 32
  special  = false
}

resource "postgresql_role" "service" {
  for_each = var.service_databases

  name     = each.value.db_user
  login    = true
  password = random_password.service_db[each.key].result

  depends_on = [module.data] # firewall rules must exist first
}

resource "postgresql_database" "service" {
  for_each = var.service_databases

  name              = each.value.db_name
  owner             = postgresql_role.service[each.key].name
  lc_collate        = "en_US.utf8"
  lc_ctype          = "en_US.utf8"
  connection_limit  = -1
  allow_connections = true
}

# --- Key Vault secrets -------------------------------------------------
locals {
  kv_id = data.terraform_remote_state.platform.outputs.key_vault_id
}

resource "random_password" "jwt_secret" {
  length  = 64
  special = false
}

resource "random_bytes" "field_encryption_key" {
  length = 32
}

resource "random_bytes" "field_encryption_hmac_key" {
  length = 32
}

resource "azurerm_key_vault_secret" "static" {
  for_each = {
    "jwt-secret"                 = random_password.jwt_secret.result
    "field-encryption-key"       = random_bytes.field_encryption_key.base64
    "field-encryption-hmac-key"  = random_bytes.field_encryption_hmac_key.base64
    "postgres-admin-password"    = module.data.postgres_admin_password
    "redis-primary-key"          = module.data.redis_primary_access_key
    "eventhub-connection-string" = module.data.eventhub_connection_string
    "storage-account-key"        = module.storage.primary_access_key
  }

  name         = each.key
  value        = each.value
  key_vault_id = local.kv_id
}

resource "azurerm_key_vault_secret" "service_db_password" {
  for_each = var.service_databases

  name         = "${each.key}-db-password"
  value        = random_password.service_db[each.key].result
  key_vault_id = local.kv_id
}
