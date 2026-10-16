/*
 * Managed data services: Postgres Flexible Server, Azure Cache for Redis, Event Hubs
 * (Kafka-compatible). Public-access mode + firewall allow-lists (AKS NAT egress IP +
 * admin_ip_cidrs) — deliberate operability trade-off, see the tree README.
 *
 * The per-service Postgres databases/roles and the Key Vault secret writes are done in the
 * 30-data root, not here — this module stands up the servers and outputs their credentials.
 */

# --- Postgres -----------------------------------------------------------
resource "random_password" "postgres_admin" {
  length  = 32
  special = false
}

resource "azurerm_postgresql_flexible_server" "main" {
  name                = "psql-${var.name_prefix}"
  resource_group_name = var.resource_group_name
  location            = var.location

  sku_name = var.postgres_sku_name
  version  = var.postgres_version

  administrator_login    = var.postgres_admin_username
  administrator_password = random_password.postgres_admin.result

  storage_mb                   = var.postgres_storage_mb
  backup_retention_days        = var.postgres_backup_retention_days
  geo_redundant_backup_enabled = var.postgres_geo_redundant_backup

  tags = var.tags
}

resource "azurerm_postgresql_flexible_server_firewall_rule" "aks_egress" {
  name             = "AllowAKSEgress"
  server_id        = azurerm_postgresql_flexible_server.main.id
  start_ip_address = var.aks_egress_ip
  end_ip_address   = var.aks_egress_ip
}

resource "azurerm_postgresql_flexible_server_firewall_rule" "admin" {
  for_each = toset(var.admin_ip_cidrs)

  name             = "AllowAdmin${replace(each.value, "/[./]/", "")}"
  server_id        = azurerm_postgresql_flexible_server.main.id
  start_ip_address = cidrhost(each.value, 0)
  end_ip_address   = cidrhost(each.value, 0)
}

# --- Redis ------------------------------------------------------------
resource "azurerm_redis_cache" "main" {
  name                = "redis-${var.name_prefix}"
  resource_group_name = var.resource_group_name
  location            = var.location

  capacity = var.redis_capacity
  family   = var.redis_family
  sku_name = var.redis_sku_name

  non_ssl_port_enabled = false
  minimum_tls_version  = "1.2"

  tags = var.tags
}

resource "azurerm_redis_firewall_rule" "aks_egress" {
  name                = "AllowAKSEgress"
  redis_cache_name    = azurerm_redis_cache.main.name
  resource_group_name = var.resource_group_name
  start_ip            = var.aks_egress_ip
  end_ip              = var.aks_egress_ip
}

resource "azurerm_redis_firewall_rule" "admin" {
  for_each = toset(var.admin_ip_cidrs)

  name                = "AllowAdmin${replace(each.value, "/[./]/", "")}"
  redis_cache_name    = azurerm_redis_cache.main.name
  resource_group_name = var.resource_group_name
  start_ip            = cidrhost(each.value, 0)
  end_ip              = cidrhost(each.value, 0)
}

# --- Event Hubs (Kafka surface) -------------------------------------
resource "azurerm_eventhub_namespace" "main" {
  name                = "evhns-${var.name_prefix}"
  resource_group_name = var.resource_group_name
  location            = var.location

  sku                 = var.eventhubs_sku
  capacity            = var.eventhubs_capacity
  minimum_tls_version = "1.2"

  network_rulesets {
    default_action                 = "Deny"
    trusted_service_access_enabled = true

    ip_rule = concat(
      [{ ip_mask = "${var.aks_egress_ip}/32", action = "Allow" }],
      [for c in var.admin_ip_cidrs : { ip_mask = c, action = "Allow" }]
    )
  }

  tags = var.tags
}

resource "azurerm_eventhub" "topic" {
  for_each = toset(var.kafka_topics)

  name              = each.value
  namespace_id      = azurerm_eventhub_namespace.main.id
  partition_count   = 2
  message_retention = 3
}

resource "azurerm_eventhub_namespace_authorization_rule" "app_services" {
  name                = "app-services"
  namespace_name      = azurerm_eventhub_namespace.main.name
  resource_group_name = var.resource_group_name
  listen              = true
  send                = true
  manage              = false
}

output "postgres_fqdn" {
  value = azurerm_postgresql_flexible_server.main.fqdn
}

output "postgres_admin_username" {
  value = var.postgres_admin_username
}

output "postgres_admin_password" {
  value     = random_password.postgres_admin.result
  sensitive = true
}

output "postgres_firewall_rule_ids" {
  description = "Depend on these before creating per-service roles so the firewall is open first."
  value = concat(
    [azurerm_postgresql_flexible_server_firewall_rule.aks_egress.id],
    [for r in azurerm_postgresql_flexible_server_firewall_rule.admin : r.id],
  )
}

output "redis_hostname" {
  value = azurerm_redis_cache.main.hostname
}

output "redis_ssl_port" {
  value = azurerm_redis_cache.main.ssl_port
}

output "redis_primary_access_key" {
  value     = azurerm_redis_cache.main.primary_access_key
  sensitive = true
}

output "eventhubs_namespace_fqdn" {
  value = "${azurerm_eventhub_namespace.main.name}.servicebus.windows.net"
}

output "eventhub_connection_string" {
  value     = azurerm_eventhub_namespace_authorization_rule.app_services.primary_connection_string
  sensitive = true
}
