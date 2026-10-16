output "rds_address" {
  value = module.data.rds_address
}

output "rds_port" {
  value = module.data.rds_port
}

output "redis_primary_endpoint" {
  value = module.data.redis_primary_endpoint
}

output "redis_port" {
  value = module.data.redis_port
}

output "kafka_mode" {
  value = module.data.kafka_mode
}

output "msk_bootstrap_brokers_sasl_scram" {
  value       = module.data.msk_bootstrap_brokers_sasl_scram
  description = "spring.kafka.bootstrap-servers when kafka_mode = msk; null for self-hosted."
}

output "emr_attachments_bucket" {
  value = module.storage.bucket_id
}

output "secretsmanager_path_prefix" {
  value       = module.secrets.path_prefix
  description = "All platform secrets under this path (plus AmazonMSK_* when kafka_mode = msk)."
}

output "service_databases" {
  description = "Per-service DB connection info. Password/JDBC URL are in Secrets Manager at '<prefix>db/<service>'."
  value = {
    for k, v in var.service_databases : k => {
      host    = module.data.rds_address
      port    = module.data.rds_port
      db_name = v.db_name
      db_user = v.db_user
      secret  = "${local.name_prefix}/db/${k}"
    }
  }
}
