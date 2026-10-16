/*
 * One-time bootstrap: creates the S3 bucket (+ DynamoDB lock table) that holds Terraform
 * remote state for `aws/envs/*`. This can't live in `envs/dev` itself — a config can't
 * create the backend it depends on before that backend exists.
 *
 * Run this once (state stays local, in this directory), note the outputs, then fill in
 * aws/envs/dev/backend.tf (or pass equivalent -backend-config values) with them.
 *
 * Mirrors azure/bootstrap/ (which creates the equivalent Azure Storage Account container).
 */

terraform {
  required_version = ">= 1.9.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = var.tags
  }
}

resource "aws_s3_bucket" "state" {
  bucket = var.state_bucket_name

  # Terraform state can contain sensitive values (connection strings, generated passwords)
  # even though envs/dev pushes secrets to Secrets Manager where possible — keep this bucket
  # locked down, encrypted and versioned so a bad `apply` can't destroy history.
  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "aws:kms"
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "state" {
  bucket                  = aws_s3_bucket.state.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_lifecycle_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    id     = "expire-noncurrent-state-versions"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = 90
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

# State locking. Terraform 1.11+ also supports S3-native locking (`use_lockfile = true` in the
# backend block, no table needed) — this DynamoDB table is the portable option that works on
# every 1.9+ toolchain, matching versions.tf's `>= 1.9.0` floor.
resource "aws_dynamodb_table" "lock" {
  name         = var.lock_table_name
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "LockID"

  attribute {
    name = "LockID"
    type = "S"
  }

  point_in_time_recovery {
    enabled = true
  }
}
