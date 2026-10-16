/*
 * Layer 20 — platform. Log Analytics, ACR, Key Vault (+ RBAC), AKS (+ workload identity).
 * Upstream: 10-network (resource group, subnet). Apply after 10.
 */

locals {
  name_prefix = "${var.project}-${var.environment}"
  tags        = merge(var.tags, { environment = var.environment })
}

data "azurerm_client_config" "current" {}

data "terraform_remote_state" "network" {
  backend = "azurerm"
  config = {
    resource_group_name  = var.tf_state_resource_group
    storage_account_name = var.tf_state_storage_account
    container_name       = var.tf_state_container
    key                  = "${var.project}/azure/${var.environment}/10-network.tfstate"
  }
}

module "monitoring" {
  source = "../../../modules/monitoring"

  name_prefix         = local.name_prefix
  resource_group_name = data.terraform_remote_state.network.outputs.resource_group_name
  location            = data.terraform_remote_state.network.outputs.location
  tags                = local.tags
  retention_in_days   = var.log_analytics_retention_days
}

module "registry" {
  source = "../../../modules/registry"

  name_prefix         = local.name_prefix
  resource_group_name = data.terraform_remote_state.network.outputs.resource_group_name
  location            = data.terraform_remote_state.network.outputs.location
  tags                = local.tags
}

module "aks" {
  source = "../../../modules/aks"

  name_prefix         = local.name_prefix
  resource_group_name = data.terraform_remote_state.network.outputs.resource_group_name
  location            = data.terraform_remote_state.network.outputs.location
  tags                = local.tags

  subnet_id                  = data.terraform_remote_state.network.outputs.aks_subnet_id
  log_analytics_workspace_id = module.monitoring.workspace_id
  acr_id                     = module.registry.id
  tenant_id                  = data.azurerm_client_config.current.tenant_id
  cluster_admin_object_ids   = [data.azurerm_client_config.current.object_id]

  kubernetes_version  = var.kubernetes_version
  system_node_vm_size = var.aks_system_node_vm_size
  system_node_count   = var.aks_system_node_count
  app_node_vm_size    = var.aks_app_node_vm_size
  app_node_min_count  = var.aks_app_node_min_count
  app_node_max_count  = var.aks_app_node_max_count
}

module "keyvault" {
  source = "../../../modules/keyvault"

  name_prefix         = local.name_prefix
  resource_group_name = data.terraform_remote_state.network.outputs.resource_group_name
  location            = data.terraform_remote_state.network.outputs.location
  tags                = local.tags

  tenant_id                      = data.azurerm_client_config.current.tenant_id
  admin_object_id                = data.azurerm_client_config.current.object_id
  workload_identity_principal_id = module.aks.workload_identity_principal_id
  admin_ip_cidrs                 = var.admin_ip_cidrs
}
