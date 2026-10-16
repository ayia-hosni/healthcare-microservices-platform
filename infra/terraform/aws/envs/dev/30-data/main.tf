/*
 * Layer 30 — data. RDS (+ per-service DBs/roles), ElastiCache, MSK (optional), S3, Secrets
 * Manager. Upstream: 10-network (subnets, data SG) + 20-platform (EKS cluster SG). Apply
 * after 20.
 */

locals {
  name_prefix = "${var.project}-${var.environment}"
  tags        = merge(var.tags, { environment = var.environment })
}

data "terraform_remote_state" "network" {
  backend = "s3"
  config = {
    bucket = var.tf_state_bucket
    key    = "${var.project}/aws/${var.environment}/10-network.tfstate"
    region = var.tf_state_region
  }
}

data "terraform_remote_state" "platform" {
  backend = "s3"
  config = {
    bucket = var.tf_state_bucket
    key    = "${var.project}/aws/${var.environment}/20-platform.tfstate"
    region = var.tf_state_region
  }
}

module "data" {
  source = "../../../modules/data"

  name_prefix = local.name_prefix
  environment = var.environment
  tags        = local.tags

  vpc_id                    = data.terraform_remote_state.network.outputs.vpc_id
  private_subnet_ids        = data.terraform_remote_state.network.outputs.private_subnet_ids
  data_security_group_id    = data.terraform_remote_state.network.outputs.data_security_group_id
  cluster_security_group_id = data.terraform_remote_state.platform.outputs.eks_cluster_security_group_id

  postgres_version         = var.postgres_version
  db_instance_class        = var.db_instance_class
  db_allocated_storage     = var.db_allocated_storage
  db_max_allocated_storage = var.db_max_allocated_storage
  db_master_username       = var.db_master_username
  db_backup_retention_days = var.db_backup_retention_days
  db_multi_az              = var.db_multi_az
  db_deletion_protection   = var.db_deletion_protection
  db_publicly_accessible   = var.db_publicly_accessible

  redis_engine_version = var.redis_engine_version
  redis_node_type      = var.redis_node_type

  kafka_mode                    = var.kafka_mode
  msk_kafka_version             = var.msk_kafka_version
  msk_broker_instance_type      = var.msk_broker_instance_type
  msk_broker_count              = var.msk_broker_count
  msk_broker_ebs_gb             = var.msk_broker_ebs_gb
  cloudwatch_log_retention_days = var.cloudwatch_log_retention_days
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

  depends_on = [module.data]
}

resource "postgresql_database" "service" {
  for_each = var.service_databases

  name              = each.value.db_name
  owner             = postgresql_role.service[each.key].name
  lc_collate        = "en_US.UTF-8"
  lc_ctype          = "en_US.UTF-8"
  connection_limit  = -1
  allow_connections = true
}

module "storage" {
  source = "../../../modules/storage"

  name_prefix = local.name_prefix
  tags        = local.tags
  bucket_name = var.emr_attachments_bucket_name
}

module "secrets" {
  source = "../../../modules/secrets"

  name_prefix = local.name_prefix
  tags        = local.tags

  rds = {
    address  = module.data.rds_address
    port     = module.data.rds_port
    username = module.data.rds_master_username
    password = module.data.rds_master_password
  }

  service_databases    = var.service_databases
  service_db_passwords = { for k, v in random_password.service_db : k => v.result }

  redis = {
    host       = module.data.redis_primary_endpoint
    port       = module.data.redis_port
    auth_token = module.data.redis_auth_token
  }
}
