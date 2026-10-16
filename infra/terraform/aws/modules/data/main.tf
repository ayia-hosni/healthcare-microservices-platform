/*
 * Managed data services: RDS PostgreSQL, ElastiCache Redis, and (when kafka_mode = "msk")
 * Amazon MSK. The per-service databases/roles are created by the postgresql provider in the
 * 30-data root, not here — this module only stands up the RDS instance itself.
 */

data "aws_region" "current" {}

# --- Security-group ingress: EKS pods -> data services --------------------
resource "aws_security_group_rule" "postgres_from_cluster" {
  type                     = "ingress"
  security_group_id        = var.data_security_group_id
  from_port                = 5432
  to_port                  = 5432
  protocol                 = "tcp"
  source_security_group_id = var.cluster_security_group_id
  description              = "EKS pods to Postgres"
}

resource "aws_security_group_rule" "redis_from_cluster" {
  type                     = "ingress"
  security_group_id        = var.data_security_group_id
  from_port                = 6379
  to_port                  = 6379
  protocol                 = "tcp"
  source_security_group_id = var.cluster_security_group_id
  description              = "EKS pods to Redis"
}

# --- RDS PostgreSQL ------------------------------------------------------
resource "random_password" "rds_master" {
  length  = 32
  special = false
}

resource "aws_db_subnet_group" "main" {
  name       = "${var.name_prefix}-rds"
  subnet_ids = var.private_subnet_ids
  tags       = var.tags
}

resource "aws_db_parameter_group" "main" {
  name   = "${var.name_prefix}-pg${var.postgres_version}"
  family = "postgres${var.postgres_version}"

  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }

  tags = var.tags

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_db_instance" "main" {
  identifier     = "psql-${var.name_prefix}"
  engine         = "postgres"
  engine_version = var.postgres_version
  instance_class = var.db_instance_class

  allocated_storage     = var.db_allocated_storage
  max_allocated_storage = var.db_max_allocated_storage
  storage_type          = "gp3"
  storage_encrypted     = true

  # No db_name: RDS always creates a default "postgres" DB, which the postgresql provider
  # connects to in order to create the per-service databases.
  username = var.db_master_username
  password = random_password.rds_master.result
  port     = 5432

  db_subnet_group_name   = aws_db_subnet_group.main.name
  parameter_group_name   = aws_db_parameter_group.main.name
  vpc_security_group_ids = [var.data_security_group_id]
  publicly_accessible    = var.db_publicly_accessible
  multi_az               = var.db_multi_az

  backup_retention_period      = var.db_backup_retention_days
  auto_minor_version_upgrade   = true
  deletion_protection          = var.db_deletion_protection
  skip_final_snapshot          = true
  apply_immediately            = true
  performance_insights_enabled = true

  tags = var.tags
}

# --- ElastiCache Redis -------------------------------------------------
resource "random_password" "redis_auth" {
  length  = 48
  special = false
}

resource "aws_elasticache_subnet_group" "main" {
  name       = "${var.name_prefix}-redis"
  subnet_ids = var.private_subnet_ids
  tags       = var.tags
}

resource "aws_elasticache_replication_group" "main" {
  replication_group_id = "redis-${var.name_prefix}"
  description          = "healthcare-platform ${var.environment} cache"

  engine         = "redis"
  engine_version = var.redis_engine_version
  node_type      = var.redis_node_type
  port           = 6379

  num_cache_clusters         = 1
  automatic_failover_enabled = false
  multi_az_enabled           = false

  subnet_group_name  = aws_elasticache_subnet_group.main.name
  security_group_ids = [var.data_security_group_id]

  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  auth_token                 = random_password.redis_auth.result

  snapshot_retention_limit = 1
  apply_immediately        = true

  tags = var.tags
}
