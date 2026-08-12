/*
 * Fill these in from `terraform output backend_config_snippet` after running bootstrap/ once
 * (or pass the same four values via `-backend-config=key=value` flags to keep them out of
 * version control if you prefer). `key` is the state file's path inside the container — give
 * each environment a distinct one if you add more later (e.g. "healthcare-platform/staging.tfstate").
 */
terraform {
  backend "azurerm" {
    resource_group_name  = "rg-healthcare-platform-tfstate"
    storage_account_name = "CHANGEME" # must match bootstrap output; globally-unique name
    container_name       = "tfstate"
    key                  = "healthcare-platform/dev.tfstate"
  }
}
