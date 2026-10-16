variable "name_prefix" {
  type = string
}

variable "resource_group_name" {
  type = string
}

variable "location" {
  type = string
}

variable "tags" {
  type    = map(string)
  default = {}
}

resource "azurerm_container_registry" "main" {
  # Globally unique, alphanumeric only.
  name                = replace("acr${var.name_prefix}", "-", "")
  resource_group_name = var.resource_group_name
  location            = var.location
  sku                 = "Standard"
  admin_enabled       = false # AKS pulls via its kubelet identity's AcrPull role, not admin creds.
  tags                = var.tags
}

output "id" {
  value = azurerm_container_registry.main.id
}

output "login_server" {
  value = azurerm_container_registry.main.login_server
}
