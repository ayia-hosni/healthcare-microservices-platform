resource "azurerm_kubernetes_cluster" "main" {
  name                = "aks-${local.name_prefix}"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  dns_prefix          = "aks-${local.name_prefix}"
  kubernetes_version  = var.kubernetes_version
  tags                = local.tags

  # Lets pods (workload identity) and the control plane authenticate to Key
  # Vault/ACR/etc. via Azure AD instead of static credentials.
  oidc_issuer_enabled       = true
  workload_identity_enabled = true

  identity {
    type = "SystemAssigned"
  }

  default_node_pool {
    name           = "system"
    vm_size        = var.aks_system_node_vm_size
    node_count     = var.aks_system_node_count
    vnet_subnet_id = azurerm_subnet.aks.id
    # true keeps app workloads off the system pool so they land on "apps" instead — but that
    # pool only exists when aks_app_node_min_count > 0 (see variables.tf; it's 0 by default
    # under the 4 vCPU POC quota cap), so fall back to false and let app pods share the system
    # pool when there's no separate pool for them to land on.
    only_critical_addons_enabled = var.aks_app_node_min_count > 0
    upgrade_settings {
      max_surge = "10%"
    }
  }

  network_profile {
    network_plugin      = "azure"
    network_plugin_mode = "overlay"
    network_policy      = "cilium"
    network_data_plane  = "cilium"
    pod_cidr            = "10.244.0.0/16"
    service_cidr        = "10.245.0.0/16"
    dns_service_ip      = "10.245.0.10"
    load_balancer_sku   = "standard"
    # We already attached our own NAT Gateway to the AKS subnet in network.tf so its IP is a
    # known quantity for firewall allow-lists elsewhere (Postgres/Redis/Storage/Event Hubs).
    outbound_type = "userAssignedNATGateway"
  }

  azure_active_directory_role_based_access_control {
    tenant_id          = data.azurerm_client_config.current.tenant_id
    azure_rbac_enabled = true
  }

  # Azure RBAC (above) is the only way in once local accounts are off — see the cluster-admin
  # role assignment below for the identity running Terraform.
  local_account_disabled = true

  oms_agent {
    log_analytics_workspace_id = azurerm_log_analytics_workspace.main.id
  }

  azure_policy_enabled = true

  lifecycle {
    ignore_changes = [kubernetes_version] # let `az aks upgrade` / auto-upgrade own this after initial create
  }
}

resource "azurerm_kubernetes_cluster_node_pool" "apps" {
  count = var.aks_app_node_min_count > 0 ? 1 : 0

  name                  = "apps"
  kubernetes_cluster_id = azurerm_kubernetes_cluster.main.id
  vm_size               = var.aks_app_node_vm_size
  vnet_subnet_id        = azurerm_subnet.aks.id
  mode                  = "User"

  auto_scaling_enabled = true
  min_count            = var.aks_app_node_min_count
  max_count            = var.aks_app_node_max_count

  node_labels = {
    workload = "app"
  }

  tags = local.tags
}

# Lets AKS pull images from ACR using its own kubelet managed identity (no imagePullSecrets).
resource "azurerm_role_assignment" "aks_acr_pull" {
  scope                = azurerm_container_registry.main.id
  role_definition_name = "AcrPull"
  principal_id         = azurerm_kubernetes_cluster.main.kubelet_identity[0].object_id
}

# The identity running `terraform apply` needs this to keep `kubectl`/Helm access after
# local_account_disabled = true switches the cluster to Azure-RBAC-only auth.
resource "azurerm_role_assignment" "aks_admin_for_apply_identity" {
  scope                = azurerm_kubernetes_cluster.main.id
  role_definition_name = "Azure Kubernetes Service RBAC Cluster Admin"
  principal_id         = data.azurerm_client_config.current.object_id
}

# ---------------------------------------------------------------------------
# Workload Identity — lets in-cluster pods (e.g. via a "platform-workload-identity"
# ServiceAccount in the healthcare-platform namespace) read secrets straight from Key Vault
# instead of the app services needing static credentials baked into k8s Secrets.
# ---------------------------------------------------------------------------

resource "azurerm_user_assigned_identity" "workload" {
  name                = "id-${local.name_prefix}-workload"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
  tags                = local.tags
}

resource "azurerm_federated_identity_credential" "workload" {
  name                = "fic-${local.name_prefix}-workload"
  resource_group_name = azurerm_resource_group.main.name
  parent_id           = azurerm_user_assigned_identity.workload.id
  audience            = ["api://AzureADTokenExchange"]
  issuer              = azurerm_kubernetes_cluster.main.oidc_issuer_url
  subject             = "system:serviceaccount:healthcare-platform:platform-workload-identity"
}
