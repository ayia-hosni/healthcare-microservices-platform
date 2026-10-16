/*
 * VPC with public + private subnets across len(var.azs) AZs.
 *
 *   public  -> Internet Gateway. Hold the NAT Gateway(s) and public ALBs the AWS Load
 *              Balancer Controller creates for the ingress.
 *   private -> NAT egress only. Hold EKS nodes/pods (VPC CNI draws pod ENIs from here),
 *              RDS, ElastiCache, MSK.
 *
 * Topology matches AWS_DEPLOYMENT_PLAN.md Phase 2 / docs/cloud/aws/networking.md.
 */

locals {
  az_count = length(var.azs)
}

resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = merge(var.tags, { Name = "vpc-${var.name_prefix}" })
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id
  tags   = merge(var.tags, { Name = "igw-${var.name_prefix}" })
}

resource "aws_subnet" "public" {
  count = local.az_count

  vpc_id                  = aws_vpc.main.id
  availability_zone       = var.azs[count.index]
  cidr_block              = cidrsubnet(var.vpc_cidr, 8, count.index)
  map_public_ip_on_launch = true

  tags = merge(var.tags, {
    Name                     = "snet-${var.name_prefix}-public-${count.index}"
    "kubernetes.io/role/elb" = "1"
  })
}

resource "aws_subnet" "private" {
  count = local.az_count

  vpc_id            = aws_vpc.main.id
  availability_zone = var.azs[count.index]
  cidr_block        = cidrsubnet(var.vpc_cidr, 4, count.index + 1)

  tags = merge(var.tags, {
    Name                              = "snet-${var.name_prefix}-private-${count.index}"
    "kubernetes.io/role/internal-elb" = "1"
  })
}

# --- NAT ---------------------------------------------------------------------
resource "aws_eip" "nat" {
  count  = var.single_nat_gateway ? 1 : local.az_count
  domain = "vpc"
  tags   = merge(var.tags, { Name = "eip-${var.name_prefix}-nat-${count.index}" })
}

resource "aws_nat_gateway" "main" {
  count = var.single_nat_gateway ? 1 : local.az_count

  allocation_id = aws_eip.nat[count.index].id
  subnet_id     = aws_subnet.public[count.index].id
  tags          = merge(var.tags, { Name = "nat-${var.name_prefix}-${count.index}" })

  depends_on = [aws_internet_gateway.main]
}

# --- Route tables ----------------------------------------------------------
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id
  tags   = merge(var.tags, { Name = "rt-${var.name_prefix}-public" })
}

resource "aws_route" "public_internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.main.id
}

resource "aws_route_table_association" "public" {
  count          = local.az_count
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table" "private" {
  count  = local.az_count
  vpc_id = aws_vpc.main.id
  tags   = merge(var.tags, { Name = "rt-${var.name_prefix}-private-${count.index}" })
}

resource "aws_route" "private_nat" {
  count                  = local.az_count
  route_table_id         = aws_route_table.private[count.index].id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = var.single_nat_gateway ? aws_nat_gateway.main[0].id : aws_nat_gateway.main[count.index].id
}

resource "aws_route_table_association" "private" {
  count          = local.az_count
  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private[count.index].id
}

# --- S3 gateway endpoint -----------------------------------------------------
# Keeps EMR-attachment traffic and ECR image-layer pulls (S3-backed) off the NAT Gateway,
# the main per-GB cost in this VPC.
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.${data.aws_region.current.name}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = aws_route_table.private[*].id

  tags = merge(var.tags, { Name = "vpce-${var.name_prefix}-s3" })
}

data "aws_region" "current" {}

# --- Shared data-services security group -----------------------------------
# RDS / ElastiCache / MSK attach this. The EKS-cluster -> data ingress rule is added in the
# data layer (it needs the cluster SG id, which doesn't exist until the platform layer).
resource "aws_security_group" "data" {
  name_prefix = "sg-${var.name_prefix}-data-"
  description = "RDS / ElastiCache / MSK - reachable from EKS pods and (optionally) admin CIDRs"
  vpc_id      = aws_vpc.main.id

  egress {
    description = "all outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.tags, { Name = "sg-${var.name_prefix}-data" })

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_security_group_rule" "data_from_admin" {
  count             = length(var.admin_ip_cidrs) > 0 ? 1 : 0
  type              = "ingress"
  security_group_id = aws_security_group.data.id
  from_port         = 5432
  to_port           = 5432
  protocol          = "tcp"
  cidr_blocks       = var.admin_ip_cidrs
  description       = "admin_ip_cidrs to Postgres (terraform apply / psql)"
}
