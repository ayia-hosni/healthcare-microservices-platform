output "resource_group_name" {
  value = module.network.resource_group_name
}

output "location" {
  value = module.network.location
}

output "vnet_id" {
  value = module.network.vnet_id
}

output "aks_subnet_id" {
  value = module.network.aks_subnet_id
}

output "nat_gateway_public_ip" {
  value       = module.network.nat_gateway_public_ip
  description = "AKS's stable egress IP — allow-listed on the data-service firewalls in 30-data."
}
