output "state_bucket_name" {
  value = aws_s3_bucket.state.id
}

output "lock_table_name" {
  value = aws_dynamodb_table.lock.name
}

output "region" {
  value = var.region
}

output "backend_config_snippet" {
  description = "Paste these values into aws/envs/dev/backend.tf (or pass via -backend-config)."
  value       = <<-EOT
    bucket         = "${aws_s3_bucket.state.id}"
    key            = "healthcare-platform/aws/dev.tfstate"
    region         = "${var.region}"
    dynamodb_table = "${aws_dynamodb_table.lock.name}"
    encrypt        = true
  EOT
}
