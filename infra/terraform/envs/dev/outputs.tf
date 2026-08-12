output "resource_group_name" {
  value = azurerm_resource_group.main.name
}

output "aks_cluster_name" {
  value       = azurerm_kubernetes_cluster.main.name
  description = "Run: az aks get-credentials --resource-group $(terraform output -raw resource_group_name) --name $(terraform output -raw aks_cluster_name) --overwrite-existing"
}

output "acr_login_server" {
  value = azurerm_container_registry.main.login_server
}

output "nat_gateway_ip" {
  value       = azurerm_public_ip.nat_gateway.ip_address
  description = "AKS's stable egress IP — already allow-listed on Postgres/Redis/Storage/Event Hubs firewalls."
}

output "postgres_fqdn" {
  value = azurerm_postgresql_flexible_server.main.fqdn
}

output "redis_hostname" {
  value = azurerm_redis_cache.main.hostname
}

output "redis_ssl_port" {
  value = azurerm_redis_cache.main.ssl_port
}

output "storage_account_name" {
  value = azurerm_storage_account.main.name
}

output "emr_attachments_container" {
  value = azurerm_storage_container.emr_attachments.name
}

output "eventhubs_namespace_fqdn" {
  value       = "${azurerm_eventhub_namespace.main.name}.servicebus.windows.net"
  description = "Kafka bootstrap-servers value (port 9093)."
}

output "key_vault_name" {
  value = azurerm_key_vault.main.name
}

output "key_vault_uri" {
  value = azurerm_key_vault.main.vault_uri
}

output "workload_identity_client_id" {
  value       = azurerm_user_assigned_identity.workload.client_id
  description = "Set as azure.workload.identity/client-id annotation on the platform-workload-identity ServiceAccount."
}

output "service_databases" {
  description = "Per-service DB connection info (host/port are shared; password lives in Key Vault as \"<service>-db-password\")."
  value = {
    for k, v in var.service_databases : k => {
      host    = azurerm_postgresql_flexible_server.main.fqdn
      port    = 5432
      db_name = v.db_name
      db_user = v.db_user
    }
  }
}
