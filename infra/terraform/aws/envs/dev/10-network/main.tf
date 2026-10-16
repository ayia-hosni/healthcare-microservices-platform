/*
 * Layer 10 — network. VPC, subnets, IGW/NAT, S3 endpoint, shared data security group.
 * No upstream layers. Apply this first.
 */

locals {
  name_prefix = "${var.project}-${var.environment}"
  tags        = merge(var.tags, { environment = var.environment })
  azs         = slice(data.aws_availability_zones.available.names, 0, var.az_count)
}

data "aws_availability_zones" "available" {
  state = "available"
  filter {
    name   = "opt-in-status"
    values = ["opt-in-not-required"]
  }
}

module "network" {
  source = "../../../modules/network"

  name_prefix        = local.name_prefix
  tags               = local.tags
  vpc_cidr           = var.vpc_cidr
  azs                = local.azs
  single_nat_gateway = var.single_nat_gateway
  admin_ip_cidrs     = var.admin_ip_cidrs
}
