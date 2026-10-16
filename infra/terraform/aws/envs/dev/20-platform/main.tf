/*
 * Layer 20 — platform. EKS cluster, IRSA roles, ECR.
 * Upstream: 10-network (VPC + subnets). Apply after 10.
 */

locals {
  name_prefix = "${var.project}-${var.environment}"
  tags        = merge(var.tags, { environment = var.environment })

  # Deterministic strings the eks module needs for the workload IRSA policy, so this layer
  # doesn't depend on 30-data / storage.
  secretsmanager_path_prefix = "${local.name_prefix}/"
  emr_bucket_arn             = "arn:${data.aws_partition.current.partition}:s3:::${local.name_prefix}-emr-attachments-${data.aws_caller_identity.current.account_id}"

}

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

data "terraform_remote_state" "network" {
  backend = "s3"
  config = {
    bucket = var.tf_state_bucket
    key    = "${var.project}/aws/${var.environment}/10-network.tfstate"
    region = var.tf_state_region
  }
}

module "eks" {
  source = "../../../modules/eks"

  name_prefix        = local.name_prefix
  tags               = local.tags
  private_subnet_ids = data.terraform_remote_state.network.outputs.private_subnet_ids
  public_subnet_ids  = data.terraform_remote_state.network.outputs.public_subnet_ids

  kubernetes_version     = var.kubernetes_version
  endpoint_public_access = var.eks_public_endpoint_enabled
  public_access_cidrs    = var.eks_public_access_cidrs

  system_node_instance_types = var.eks_system_node_instance_types
  system_node_desired_size   = var.eks_system_node_desired_size
  system_node_min_size       = var.eks_system_node_min_size
  system_node_max_size       = var.eks_system_node_max_size

  app_node_instance_types = var.eks_app_node_instance_types
  app_node_min_size       = var.eks_app_node_min_size
  app_node_desired_size   = var.eks_app_node_desired_size
  app_node_max_size       = var.eks_app_node_max_size
  app_node_capacity_type  = var.eks_app_node_capacity_type

  enable_container_insights     = var.enable_container_insights
  cloudwatch_log_retention_days = var.cloudwatch_log_retention_days

  cluster_admin_principal_arns = [data.aws_caller_identity.current.arn]

  secretsmanager_path_prefix = local.secretsmanager_path_prefix
  emr_bucket_arn             = local.emr_bucket_arn
  kafka_mode                 = var.kafka_mode
}

module "ecr" {
  source = "../../../modules/ecr"

  name_prefix           = local.name_prefix
  tags                  = local.tags
  service_images        = var.service_images
  image_retention_count = var.ecr_image_retention_count
}
