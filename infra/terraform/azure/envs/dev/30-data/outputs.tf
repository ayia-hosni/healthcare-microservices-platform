output "postgres_fqdn" {
  value = module.data.postgres_fqdn
}

output "redis_hostname" {
  value = module.data.redis_hostname
}

output "redis_ssl_port" {
  value = module.data.redis_ssl_port
}

output "eventhubs_namespace_fqdn" {
  value       = module.data.eventhubs_namespace_fqdn
  description = "Kafka bootstrap-servers value (port 9093)."
}

output "storage_account_name" {
  value = module.storage.account_name
}

output "emr_attachments_container" {
  value = module.storage.container_name
}

output "service_databases" {
  description = "Per-service DB connection info. Password is in Key Vault as \"<service>-db-password\"."
  value = {
    for k, v in var.service_databases : k => {
      host    = module.data.postgres_fqdn
      port    = 5432
      db_name = v.db_name
      db_user = v.db_user
    }
  }
}
