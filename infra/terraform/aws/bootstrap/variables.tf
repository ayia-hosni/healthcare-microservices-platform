variable "project" {
  description = "Short project name used to derive resource names."
  type        = string
  default     = "healthcare-platform"
}

variable "region" {
  description = "AWS region for the state bucket. Keep this the same as (or close to) the region in aws/envs/dev."
  type        = string
  default     = "us-east-1"
}

variable "state_bucket_name" {
  description = <<-EOT
    Globally-unique S3 bucket name for Terraform remote state. S3 bucket names share one
    global namespace, so the default here will likely collide — override it, e.g.
    "healthcare-platform-tfstate-<yourinitials>-<random4>".
  EOT
  type        = string
  default     = "healthcare-platform-tfstate"
}

variable "lock_table_name" {
  description = "DynamoDB table name for state locking."
  type        = string
  default     = "healthcare-platform-tflock"
}

variable "tags" {
  description = "Tags applied to bootstrap resources."
  type        = map(string)
  default = {
    project   = "healthcare-platform"
    purpose   = "terraform-state"
    managedby = "terraform"
  }
}
