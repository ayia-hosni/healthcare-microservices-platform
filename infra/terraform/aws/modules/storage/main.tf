/*
 * S3 bucket for EMR document attachments — replaces MinIO. Close to a drop-in: the MinIO
 * Java SDK speaks the S3 API, so emr-service points at S3 with config changes (region
 * endpoint, path-style off, IRSA credentials) rather than an SDK swap. Access is via the
 * workload IRSA role (eks module), scoped to this bucket.
 */

variable "name_prefix" {
  type = string
}

variable "tags" {
  type    = map(string)
  default = {}
}

variable "bucket_name" {
  description = "Empty = derived as \"<name_prefix>-emr-attachments-<account-id>\"."
  type        = string
  default     = ""
}

data "aws_caller_identity" "current" {}

locals {
  bucket_name = var.bucket_name != "" ? var.bucket_name : "${var.name_prefix}-emr-attachments-${data.aws_caller_identity.current.account_id}"
}

resource "aws_s3_bucket" "emr" {
  bucket = local.bucket_name
  tags   = var.tags
}

resource "aws_s3_bucket_public_access_block" "emr" {
  bucket                  = aws_s3_bucket.emr.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "emr" {
  bucket = aws_s3_bucket.emr.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "emr" {
  bucket = aws_s3_bucket.emr.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "aws:kms"
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "emr" {
  bucket = aws_s3_bucket.emr.id

  rule {
    id     = "abort-incomplete-multipart"
    status = "Enabled"
    filter {}
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  rule {
    id     = "expire-noncurrent-versions"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration {
      noncurrent_days = 90
    }
  }
}

data "aws_iam_policy_document" "emr" {
  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.emr.arn, "${aws_s3_bucket.emr.arn}/*"]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "emr" {
  bucket = aws_s3_bucket.emr.id
  policy = data.aws_iam_policy_document.emr.json
}

output "bucket_id" {
  value = aws_s3_bucket.emr.id
}

output "bucket_arn" {
  value = aws_s3_bucket.emr.arn
}
