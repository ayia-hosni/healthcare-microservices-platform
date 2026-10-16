terraform {
  required_version = ">= 1.9.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
    postgresql = {
      source  = "cyrilgdn/postgresql"
      version = "~> 1.22"
    }
  }
}

provider "aws" {
  region = var.region
  default_tags {
    tags = local.tags
  }
}

# Authenticates as the RDS master user to create one login role + database per microservice
# (mirrors infra/docker/init-multiple-dbs.sh). Needs network access to RDS on 5432 — that's
# the db_publicly_accessible trade-off (see the module + README). On the very first apply the
# host is unknown until RDS exists; Terraform defers the postgresql_* resources accordingly.
provider "postgresql" {
  host            = module.data.rds_address
  port            = module.data.rds_port
  username        = module.data.rds_master_username
  password        = module.data.rds_master_password
  sslmode         = "require"
  superuser       = false
  connect_timeout = 30
}
