/*
 * AWS Secrets Manager entries under the "<name_prefix>/" path, read at runtime by app pods
 * through the workload IRSA role (eks module) — e.g. via External Secrets Operator or the
 * Secrets Store CSI driver. Parity with the Azure tree writing the same set to Key Vault.
 *
 * The MSK SCRAM secret is NOT here — it lives in the data module (name must start
 * "AmazonMSK_", and it's created before the SCRAM association).
 *
 * These values also live in Terraform state (S3, SSE-KMS) — treat the state bucket as
 * sensitive.
 */

variable "name_prefix" {
  type = string
}

variable "tags" {
  type    = map(string)
  default = {}
}

variable "recovery_window_in_days" {
  type    = number
  default = 7
}

variable "rds" {
  description = "{ address, port, username, password } for the RDS master."
  type = object({
    address  = string
    port     = number
    username = string
    password = string
  })
}

variable "service_databases" {
  type = map(object({
    db_name = string
    db_user = string
  }))
}

variable "service_db_passwords" {
  description = "service key -> generated password (created in the 30-data root alongside the postgresql roles)."
  type        = map(string)
}

variable "redis" {
  type = object({
    host       = string
    port       = number
    auth_token = string
  })
}

locals {
  prefix = "${var.name_prefix}/"
}

# --- Application secrets ------------------------------------------------
resource "random_password" "jwt_secret" {
  length  = 64
  special = false
}

resource "random_bytes" "field_encryption_key" {
  length = 32 # AES-256; consumed base64-encoded (see infra/k8s/base/secrets.yaml)
}

resource "random_bytes" "field_encryption_hmac_key" {
  length = 32
}

resource "aws_secretsmanager_secret" "simple" {
  for_each = tomap({
    "jwt-secret"                = random_password.jwt_secret.result
    "field-encryption-key"      = random_bytes.field_encryption_key.base64
    "field-encryption-hmac-key" = random_bytes.field_encryption_hmac_key.base64
  })

  name                    = "${local.prefix}${each.key}"
  recovery_window_in_days = var.recovery_window_in_days
  tags                    = var.tags
}

resource "aws_secretsmanager_secret_version" "simple" {
  for_each = aws_secretsmanager_secret.simple

  secret_id = each.value.id
  secret_string = {
    "jwt-secret"                = random_password.jwt_secret.result
    "field-encryption-key"      = random_bytes.field_encryption_key.base64
    "field-encryption-hmac-key" = random_bytes.field_encryption_hmac_key.base64
  }[each.key]
}

# --- RDS master -------------------------------------------------------
resource "aws_secretsmanager_secret" "rds_master" {
  name                    = "${local.prefix}rds-master"
  recovery_window_in_days = var.recovery_window_in_days
  tags                    = var.tags
}

resource "aws_secretsmanager_secret_version" "rds_master" {
  secret_id = aws_secretsmanager_secret.rds_master.id
  secret_string = jsonencode({
    host     = var.rds.address
    port     = var.rds.port
    username = var.rds.username
    password = var.rds.password
  })
}

# --- Per-service DB credentials -------------------------------------
resource "aws_secretsmanager_secret" "service_db" {
  for_each = var.service_databases

  name                    = "${local.prefix}db/${each.key}"
  recovery_window_in_days = var.recovery_window_in_days
  tags                    = var.tags
}

resource "aws_secretsmanager_secret_version" "service_db" {
  for_each = var.service_databases

  secret_id = aws_secretsmanager_secret.service_db[each.key].id
  secret_string = jsonencode({
    host     = var.rds.address
    port     = var.rds.port
    dbname   = each.value.db_name
    username = each.value.db_user
    password = var.service_db_passwords[each.key]
    jdbcUrl  = "jdbc:postgresql://${var.rds.address}:${var.rds.port}/${each.value.db_name}?sslmode=require"
  })
}

# --- Redis ---------------------------------------------------------
resource "aws_secretsmanager_secret" "redis" {
  name                    = "${local.prefix}redis"
  recovery_window_in_days = var.recovery_window_in_days
  tags                    = var.tags
}

resource "aws_secretsmanager_secret_version" "redis" {
  secret_id = aws_secretsmanager_secret.redis.id
  secret_string = jsonencode({
    host      = var.redis.host
    port      = var.redis.port
    authToken = var.redis.auth_token
    tls       = true
  })
}

output "path_prefix" {
  value = local.prefix
}
