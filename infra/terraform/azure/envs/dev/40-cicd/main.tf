/*
 * Layer 40 — CI/CD. GitHub Actions Entra app + federated credential + role assignments
 * (AcrPush, AKS RBAC Cluster Admin). Upstream: 20-platform (ACR + AKS). Apply after 20
 * (independent of 30).
 */

locals {
  name_prefix = "${var.project}-${var.environment}"
}

data "azurerm_client_config" "current" {}

data "terraform_remote_state" "platform" {
  backend = "azurerm"
  config = {
    resource_group_name  = var.tf_state_resource_group
    storage_account_name = var.tf_state_storage_account
    container_name       = var.tf_state_container
    key                  = "${var.project}/azure/${var.environment}/20-platform.tfstate"
  }
}

module "cicd" {
  source = "../../../modules/cicd"

  name_prefix    = local.name_prefix
  github_repo    = var.github_repo
  branch         = var.github_oidc_branch
  acr_id         = data.terraform_remote_state.platform.outputs.acr_id
  aks_cluster_id = data.terraform_remote_state.platform.outputs.aks_cluster_id
}

output "github_actions_client_id" {
  value       = module.cicd.client_id
  description = "Set as the GitHub repo variable AZURE_CLIENT_ID."
}

output "github_actions_tenant_id" {
  value       = data.azurerm_client_config.current.tenant_id
  description = "Set as the GitHub repo variable AZURE_TENANT_ID."
}

output "github_actions_subscription_id" {
  value       = data.azurerm_client_config.current.subscription_id
  description = "Set as the GitHub repo variable AZURE_SUBSCRIPTION_ID."
}
