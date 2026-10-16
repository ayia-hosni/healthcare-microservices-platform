terraform {
  backend "azurerm" {
    resource_group_name  = "rg-healthcare-platform-tfstate"
    storage_account_name = "sthealthplatformtfstate"
    container_name       = "tfstate"
    key                  = "healthcare-platform/azure/dev/30-data.tfstate"
  }
}
