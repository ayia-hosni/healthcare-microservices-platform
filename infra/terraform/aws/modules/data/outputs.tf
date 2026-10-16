output "rds_address" {
  value = aws_db_instance.main.address
}

output "rds_port" {
  value = 5432
}

output "rds_master_username" {
  value = aws_db_instance.main.username
}

output "rds_master_password" {
  value     = random_password.rds_master.result
  sensitive = true
}

output "redis_primary_endpoint" {
  value = aws_elasticache_replication_group.main.primary_endpoint_address
}

output "redis_port" {
  value = 6379
}

output "redis_auth_token" {
  value     = random_password.redis_auth.result
  sensitive = true
}

output "kafka_mode" {
  value = var.kafka_mode
}

output "msk_bootstrap_brokers_sasl_scram" {
  value = local.msk_enabled ? aws_msk_cluster.main[0].bootstrap_brokers_sasl_scram : null
}

output "msk_scram_secret_arn" {
  value = local.msk_enabled ? aws_secretsmanager_secret.msk_scram[0].arn : null
}
