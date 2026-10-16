/*
 * Fill from `terraform output backend_config_snippet` in azure/bootstrap/ (or pass via
 * -backend-config). Each layer uses a distinct key so they lock/apply independently.
 */
terraform {
  backend "azurerm" {
    resource_group_name  = "rg-healthcare-platform-tfstate"
    storage_account_name = "sthealthplatformtfstate"
    container_name       = "tfstate"
    key                  = "healthcare-platform/azure/dev/10-network.tfstate"
  }
}
