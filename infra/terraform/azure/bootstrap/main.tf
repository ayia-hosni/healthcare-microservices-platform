/*
 * One-time bootstrap: creates the storage account that holds Terraform remote state for the
 * `envs/*` configs. This can't live in `envs/dev` itself — a config can't create the backend
 * it depends on before that backend exists.
 *
 * Run this once (state stays local, in this directory), note the outputs, then fill in
 * envs/dev/backend.tf (or pass equivalent -backend-config values) with them.
 */

terraform {
  required_version = ">= 1.9.0"

  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 4.0"
    }
  }
}

provider "azurerm" {
  features {}
}

resource "azurerm_resource_group" "state" {
  name     = "rg-${var.project}-tfstate"
  location = var.location
  tags     = var.tags
}

resource "azurerm_storage_account" "state" {
  name                     = var.storage_account_name
  resource_group_name      = azurerm_resource_group.state.name
  location                 = azurerm_resource_group.state.location
  account_tier             = "Standard"
  account_replication_type = "GRS"
  min_tls_version          = "TLS1_2"

  # Terraform state can contain sensitive values (connection strings, etc.) even though we
  # push secrets to Key Vault where possible — keep this locked down and versioned so a bad
  # `apply` doesn't destroy history.
  blob_properties {
    versioning_enabled = true

    delete_retention_policy {
      days = 30
    }
  }

  tags = var.tags
}

resource "azurerm_storage_container" "tfstate" {
  name                  = "tfstate"
  storage_account_id    = azurerm_storage_account.state.id
  container_access_type = "private"
}
