output "eks_cluster_name" {
  value       = module.eks.cluster_name
  description = "aws eks update-kubeconfig --region <region> --name <this>"
}

output "eks_cluster_arn" {
  value = module.eks.cluster_arn
}

output "eks_cluster_endpoint" {
  value = module.eks.cluster_endpoint
}

output "eks_cluster_security_group_id" {
  value = module.eks.cluster_security_group_id
}

output "oidc_provider_arn" {
  value = module.eks.oidc_provider_arn
}

output "oidc_provider_url" {
  value = module.eks.oidc_provider_url
}

output "workload_iam_role_arn" {
  value       = module.eks.workload_iam_role_arn
  description = "Annotate the 'platform-workload-identity' ServiceAccount in the 'healthcare-platform' namespace with eks.amazonaws.com/role-arn = this."
}

output "alb_controller_iam_role_arn" {
  value = module.eks.alb_controller_iam_role_arn
}

output "ecr_registry" {
  value = module.ecr.registry
}

output "ecr_repository_urls" {
  value = module.ecr.repository_urls
}

output "ecr_repository_arns" {
  value = module.ecr.repository_arns
}
