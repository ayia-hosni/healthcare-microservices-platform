resource "azurerm_virtual_network" "main" {
  name                = "vnet-${local.name_prefix}"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  address_space       = ["10.20.0.0/16"]
  tags                = local.tags
}

# Azure CNI Overlay gives pods addresses from a separate overlay CIDR (set in aks.tf), so this
# subnet only needs to size for nodes, not pods+nodes — /20 (4096 addrs) leaves plenty of
# headroom for the app node pool to autoscale.
resource "azurerm_subnet" "aks" {
  name                 = "snet-aks"
  resource_group_name  = azurerm_resource_group.main.name
  virtual_network_name = azurerm_virtual_network.main.name
  address_prefixes     = ["10.20.0.0/20"]
}

resource "azurerm_network_security_group" "aks" {
  name                = "nsg-${local.name_prefix}-aks"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = local.tags

  # AKS manages the load-balancer/ingress rules it needs on this NSG itself; we intentionally
  # add no custom rules here beyond Azure's secure defaults (deny internet inbound). This
  # resource exists so future rules have a documented home instead of relying on the
  # AKS-managed one implicitly.
}

resource "azurerm_subnet_network_security_group_association" "aks" {
  subnet_id                 = azurerm_subnet.aks.id
  network_security_group_id = azurerm_network_security_group.aks.id
}

# Stable, known egress IP for the cluster, so Postgres/Redis/Storage/Event Hubs firewalls can
# allow-list AKS's traffic by IP without opening up to the whole internet. Also avoids the
# SNAT port exhaustion issues the AKS load-balancer's default outbound rule can hit under load.
resource "azurerm_public_ip" "nat_gateway" {
  name                = "pip-${local.name_prefix}-nat"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  allocation_method   = "Static"
  sku                 = "Standard"
  # No explicit zone: avoids a capacity error on a constrained/trial subscription for no
  # POC-stage benefit.
  tags = local.tags
}

resource "azurerm_nat_gateway" "main" {
  name                    = "nat-${local.name_prefix}"
  location                = azurerm_resource_group.main.location
  resource_group_name     = azurerm_resource_group.main.name
  sku_name                = "Standard"
  idle_timeout_in_minutes = 10
  tags                    = local.tags
}

resource "azurerm_nat_gateway_public_ip_association" "main" {
  nat_gateway_id       = azurerm_nat_gateway.main.id
  public_ip_address_id = azurerm_public_ip.nat_gateway.id
}

resource "azurerm_subnet_nat_gateway_association" "aks" {
  subnet_id      = azurerm_subnet.aks.id
  nat_gateway_id = azurerm_nat_gateway.main.id
}
