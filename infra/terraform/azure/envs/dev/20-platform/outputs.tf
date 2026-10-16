output "aks_cluster_name" {
  value       = module.aks.cluster_name
  description = "az aks get-credentials --resource-group <rg> --name <this> --overwrite-existing"
}

output "aks_cluster_id" {
  value = module.aks.cluster_id
}

output "aks_oidc_issuer_url" {
  value = module.aks.oidc_issuer_url
}

output "acr_id" {
  value = module.registry.id
}

output "acr_login_server" {
  value = module.registry.login_server
}

output "log_analytics_workspace_id" {
  value = module.monitoring.workspace_id
}

output "key_vault_id" {
  value = module.keyvault.id
}

output "key_vault_uri" {
  value = module.keyvault.uri
}

output "key_vault_name" {
  value = module.keyvault.name
}

output "workload_identity_client_id" {
  value       = module.aks.workload_identity_client_id
  description = "Set as the azure.workload.identity/client-id annotation on the platform-workload-identity ServiceAccount."
}

output "github_actions_tenant_id" {
  value = data.azurerm_client_config.current.tenant_id
}

output "github_actions_subscription_id" {
  value = data.azurerm_client_config.current.subscription_id
}
