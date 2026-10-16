terraform {
  backend "s3" {
    bucket         = "healthcare-platform-tfstate"
    key            = "healthcare-platform/aws/dev/30-data.tfstate"
    region         = "us-east-1"
    dynamodb_table = "healthcare-platform-tflock"
    encrypt        = true
  }
}
