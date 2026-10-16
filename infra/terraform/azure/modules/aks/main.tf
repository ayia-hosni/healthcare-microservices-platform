resource "azurerm_kubernetes_cluster" "main" {
  name                = "aks-${var.name_prefix}"
  location            = var.location
  resource_group_name = var.resource_group_name
  dns_prefix          = "aks-${var.name_prefix}"
  kubernetes_version  = var.kubernetes_version
  tags                = var.tags

  oidc_issuer_enabled       = true
  workload_identity_enabled = true

  identity {
    type = "SystemAssigned"
  }

  default_node_pool {
    name           = "system"
    vm_size        = var.system_node_vm_size
    node_count     = var.system_node_count
    vnet_subnet_id = var.subnet_id
    # Keep app workloads off the system pool only when a dedicated "apps" pool exists.
    only_critical_addons_enabled = var.app_node_min_count > 0
    upgrade_settings {
      max_surge = "10%"
    }
  }

  network_profile {
    network_plugin      = "azure"
    network_plugin_mode = "overlay"
    network_policy      = "cilium"
    network_data_plane  = "cilium"
    pod_cidr            = var.pod_cidr
    service_cidr        = var.service_cidr
    dns_service_ip      = var.dns_service_ip
    load_balancer_sku   = "standard"
    outbound_type       = "userAssignedNATGateway"
  }

  azure_active_directory_role_based_access_control {
    tenant_id          = var.tenant_id
    azure_rbac_enabled = true
  }

  local_account_disabled = true

  oms_agent {
    log_analytics_workspace_id = var.log_analytics_workspace_id
  }

  azure_policy_enabled = true

  lifecycle {
    ignore_changes = [kubernetes_version]
  }
}

resource "azurerm_kubernetes_cluster_node_pool" "apps" {
  count = var.app_node_min_count > 0 ? 1 : 0

  name                  = "apps"
  kubernetes_cluster_id = azurerm_kubernetes_cluster.main.id
  vm_size               = var.app_node_vm_size
  vnet_subnet_id        = var.subnet_id
  mode                  = "User"

  auto_scaling_enabled = true
  min_count            = var.app_node_min_count
  max_count            = var.app_node_max_count

  node_labels = { workload = "app" }
  tags        = var.tags
}

resource "azurerm_role_assignment" "aks_acr_pull" {
  scope                = var.acr_id
  role_definition_name = "AcrPull"
  principal_id         = azurerm_kubernetes_cluster.main.kubelet_identity[0].object_id
}

resource "azurerm_role_assignment" "cluster_admin" {
  for_each             = toset(var.cluster_admin_object_ids)
  scope                = azurerm_kubernetes_cluster.main.id
  role_definition_name = "Azure Kubernetes Service RBAC Cluster Admin"
  principal_id         = each.value
}

# --- Workload identity — lets in-cluster pods read Key Vault secrets ---
resource "azurerm_user_assigned_identity" "workload" {
  name                = "id-${var.name_prefix}-workload"
  resource_group_name = var.resource_group_name
  location            = var.location
  tags                = var.tags
}

resource "azurerm_federated_identity_credential" "workload" {
  name                = "fic-${var.name_prefix}-workload"
  resource_group_name = var.resource_group_name
  parent_id           = azurerm_user_assigned_identity.workload.id
  audience            = ["api://AzureADTokenExchange"]
  issuer              = azurerm_kubernetes_cluster.main.oidc_issuer_url
  subject             = "system:serviceaccount:${var.workload_namespace}:${var.workload_service_account}"
}

output "cluster_name" {
  value = azurerm_kubernetes_cluster.main.name
}

output "cluster_id" {
  value = azurerm_kubernetes_cluster.main.id
}

output "oidc_issuer_url" {
  value = azurerm_kubernetes_cluster.main.oidc_issuer_url
}

output "kubelet_identity_object_id" {
  value = azurerm_kubernetes_cluster.main.kubelet_identity[0].object_id
}

output "workload_identity_client_id" {
  value = azurerm_user_assigned_identity.workload.client_id
}

output "workload_identity_principal_id" {
  value = azurerm_user_assigned_identity.workload.principal_id
}
