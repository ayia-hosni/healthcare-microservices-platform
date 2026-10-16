terraform {
  required_version = ">= 1.9.0"
  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 4.0"
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

provider "azurerm" {
  features {
    key_vault {
      purge_soft_delete_on_destroy    = false
      recover_soft_deleted_key_vaults = true
    }
  }
}

# Authenticates as the Postgres server admin to create one login role + database per
# microservice (mirrors infra/docker/init-multiple-dbs.sh). Needs to reach the server on
# 5432 — see the firewall rules in the data module and the public-access note in the README.
provider "postgresql" {
  host            = module.data.postgres_fqdn
  port            = 5432
  username        = module.data.postgres_admin_username
  password        = module.data.postgres_admin_password
  sslmode         = "require"
  superuser       = false
  connect_timeout = 30
}
