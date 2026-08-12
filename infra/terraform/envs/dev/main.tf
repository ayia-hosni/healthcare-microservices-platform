locals {
  name_prefix = "${var.project}-${var.environment}"

  tags = merge(var.tags, {
    environment = var.environment
  })
}

resource "azurerm_resource_group" "main" {
  name     = "rg-${local.name_prefix}"
  location = var.location
  tags     = local.tags
}

data "azurerm_client_config" "current" {}
