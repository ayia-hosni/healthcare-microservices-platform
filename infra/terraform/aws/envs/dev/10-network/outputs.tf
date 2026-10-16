output "vpc_id" {
  value = module.network.vpc_id
}

output "vpc_cidr" {
  value = module.network.vpc_cidr
}

output "public_subnet_ids" {
  value = module.network.public_subnet_ids
}

output "private_subnet_ids" {
  value = module.network.private_subnet_ids
}

output "private_route_table_ids" {
  value = module.network.private_route_table_ids
}

output "data_security_group_id" {
  value = module.network.data_security_group_id
}
