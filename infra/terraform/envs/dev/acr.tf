resource "azurerm_container_registry" "main" {
  # Globally unique, alphanumeric only.
  name                = replace("acr${local.name_prefix}", "-", "")
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
  sku                 = "Standard"
  admin_enabled       = false # AKS pulls via its kubelet identity's AcrPull role (see aks.tf), not admin credentials.
  tags                = local.tags
}
