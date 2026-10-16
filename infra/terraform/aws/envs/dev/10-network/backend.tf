/*
 * Fill from `terraform output backend_config_snippet` in aws/bootstrap/ (or pass via
 * -backend-config). Each layer uses a distinct key so they lock/apply independently.
 */
terraform {
  backend "s3" {
    bucket         = "healthcare-platform-tfstate"
    key            = "healthcare-platform/aws/dev/10-network.tfstate"
    region         = "us-east-1"
    dynamodb_table = "healthcare-platform-tflock"
    encrypt        = true
  }
}
