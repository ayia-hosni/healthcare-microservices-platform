resource "azurerm_redis_cache" "main" {
  name                = "redis-${local.name_prefix}"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location

  capacity = var.redis_capacity
  family   = var.redis_family
  sku_name = var.redis_sku_name

  non_ssl_port_enabled = false
  minimum_tls_version  = "1.2"

  tags = local.tags
}

resource "azurerm_redis_firewall_rule" "aks_egress" {
  name                = "AllowAKSEgress"
  redis_cache_name    = azurerm_redis_cache.main.name
  resource_group_name = azurerm_resource_group.main.name
  start_ip            = azurerm_public_ip.nat_gateway.ip_address
  end_ip              = azurerm_public_ip.nat_gateway.ip_address
}

resource "azurerm_redis_firewall_rule" "admin_access" {
  for_each = toset(var.admin_ip_cidrs)

  name                = "AllowAdmin${replace(each.value, "/[./]/", "")}"
  redis_cache_name    = azurerm_redis_cache.main.name
  resource_group_name = azurerm_resource_group.main.name
  start_ip            = cidrhost(each.value, 0)
  end_ip              = cidrhost(each.value, 0)
}

resource "azurerm_key_vault_secret" "redis_primary_key" {
  name         = "redis-primary-key"
  value        = azurerm_redis_cache.main.primary_access_key
  key_vault_id = azurerm_key_vault.main.id
  depends_on   = [azurerm_role_assignment.keyvault_admin_for_apply_identity]
}
