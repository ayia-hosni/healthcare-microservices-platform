/*
 * Layer 10 — network. Resource group, VNet, AKS subnet + NSG, NAT Gateway.
 * No upstream layers. Apply this first.
 */

locals {
  name_prefix = "${var.project}-${var.environment}"
  tags        = merge(var.tags, { environment = var.environment })
}

module "network" {
  source = "../../../modules/network"

  name_prefix        = local.name_prefix
  location           = var.location
  tags               = local.tags
  vnet_address_space = var.vnet_address_space
  aks_subnet_prefix  = var.aks_subnet_prefix
}
