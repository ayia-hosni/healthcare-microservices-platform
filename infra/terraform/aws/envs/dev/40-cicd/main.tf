/*
 * Layer 40 — CI/CD. GitHub Actions OIDC provider + role (ECR push + EKS cluster-admin access
 * entry). Upstream: 20-platform (cluster + ECR repos). Apply after 20 (independent of 30).
 */

locals {
  name_prefix = "${var.project}-${var.environment}"
  tags        = merge(var.tags, { environment = var.environment })
}

data "terraform_remote_state" "platform" {
  backend = "s3"
  config = {
    bucket = var.tf_state_bucket
    key    = "${var.project}/aws/${var.environment}/20-platform.tfstate"
    region = var.tf_state_region
  }
}

module "cicd" {
  source = "../../../modules/cicd"

  name_prefix          = local.name_prefix
  tags                 = local.tags
  github_repo          = var.github_repo
  branch               = var.github_oidc_branch
  create_oidc_provider = var.create_github_oidc_provider

  eks_cluster_name    = data.terraform_remote_state.platform.outputs.eks_cluster_name
  eks_cluster_arn     = data.terraform_remote_state.platform.outputs.eks_cluster_arn
  ecr_repository_arns = data.terraform_remote_state.platform.outputs.ecr_repository_arns
}

output "github_actions_role_arn" {
  value       = module.cicd.github_actions_role_arn
  description = "Set as the GitHub repo variable AWS_ROLE_ARN (aws-actions/configure-aws-credentials)."
}
