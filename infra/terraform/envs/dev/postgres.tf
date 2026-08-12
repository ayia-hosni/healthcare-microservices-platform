/*
 * One Flexible Server, one database + one login role per microservice — mirrors
 * docs/adr/0001-database-per-service.md and infra/docker/init-multiple-dbs.sh.
 *
 * Deployed in public-access mode with a firewall allow-list (AKS's NAT gateway IP + your
 * admin_ip_cidrs), not the more locked-down VNet-integrated private-access mode. That's a
 * deliberate trade for operability: private access needs a delegated subnet + private DNS
 * zone, and `terraform apply`'s postgresql provider connection (used below to create the
 * per-service roles/databases) would then only work from inside the VNet, which usually means
 * a bastion/VPN/self-hosted CI runner. If you have PHI in this database and that networking
 * is already in place, switch this to private access — see README.md.
 */

resource "random_password" "postgres_admin" {
  length  = 32
  special = false # avoid characters Postgres connection strings/JDBC URLs need escaping
}

resource "azurerm_postgresql_flexible_server" "main" {
  name                = "psql-${local.name_prefix}"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location

  sku_name = var.postgres_sku_name
  version  = var.postgres_version

  administrator_login    = var.postgres_admin_username
  administrator_password = random_password.postgres_admin.result

  storage_mb                   = var.postgres_storage_mb
  backup_retention_days        = var.postgres_backup_retention_days
  geo_redundant_backup_enabled = var.postgres_geo_redundant_backup

  zone = "1"

  tags = local.tags

  lifecycle {
    ignore_changes = [zone] # Azure may rebalance the AZ; not worth a forced replace over
  }
}

resource "azurerm_postgresql_flexible_server_firewall_rule" "aks_egress" {
  name             = "AllowAKSEgress"
  server_id        = azurerm_postgresql_flexible_server.main.id
  start_ip_address = azurerm_public_ip.nat_gateway.ip_address
  end_ip_address   = azurerm_public_ip.nat_gateway.ip_address
}

resource "azurerm_postgresql_flexible_server_firewall_rule" "admin_access" {
  for_each = toset(var.admin_ip_cidrs)

  name      = "AllowAdmin${replace(each.value, "/[./]/", "")}"
  server_id = azurerm_postgresql_flexible_server.main.id
  # Firewall rules are IP ranges, not CIDRs — this assumes /32 entries in admin_ip_cidrs.
  start_ip_address = cidrhost(each.value, 0)
  end_ip_address   = cidrhost(each.value, 0)
}

resource "azurerm_key_vault_secret" "postgres_admin_password" {
  name         = "postgres-admin-password"
  value        = random_password.postgres_admin.result
  key_vault_id = azurerm_key_vault.main.id
  depends_on   = [azurerm_role_assignment.keyvault_admin_for_apply_identity]
}

resource "random_password" "service_db_password" {
  for_each = var.service_databases

  length  = 32
  special = false
}

resource "postgresql_role" "service" {
  for_each = var.service_databases

  name     = each.value.db_user
  login    = true
  password = random_password.service_db_password[each.key].result

  depends_on = [
    azurerm_postgresql_flexible_server_firewall_rule.aks_egress,
    azurerm_postgresql_flexible_server_firewall_rule.admin_access,
  ]
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

resource "azurerm_key_vault_secret" "service_db_password" {
  for_each = var.service_databases

  name         = "${each.key}-db-password"
  value        = random_password.service_db_password[each.key].result
  key_vault_id = azurerm_key_vault.main.id
  depends_on   = [azurerm_role_assignment.keyvault_admin_for_apply_identity]
}
