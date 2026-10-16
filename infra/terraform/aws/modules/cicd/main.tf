/*
 * GitHub Actions OIDC federation — short-lived OIDC token instead of stored access keys, to
 * push images to ECR and deploy to EKS. Parity with the Azure tree's github-oidc.tf.
 * Only pushes to var.branch can assume the role (subject pinned to that ref).
 */

variable "name_prefix" {
  type = string
}

variable "tags" {
  type    = map(string)
  default = {}
}

variable "github_repo" {
  description = "owner/repo"
  type        = string
}

variable "branch" {
  type    = string
  default = "main"
}

variable "create_oidc_provider" {
  description = "false if token.actions.githubusercontent.com is already an IAM OIDC provider in this account (account-global singleton)."
  type        = bool
  default     = true
}

variable "eks_cluster_name" {
  type = string
}

variable "eks_cluster_arn" {
  type = string
}

variable "ecr_repository_arns" {
  type = list(string)
}

data "aws_partition" "current" {}

data "aws_iam_openid_connect_provider" "existing" {
  count = var.create_oidc_provider ? 0 : 1
  url   = "https://token.actions.githubusercontent.com"
}

resource "aws_iam_openid_connect_provider" "github" {
  count          = var.create_oidc_provider ? 1 : 0
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
  # GitHub's OIDC certs chain to a well-known root; AWS validates the chain and effectively
  # ignores this value, but the argument is still required.
  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea1"]
  tags            = var.tags
}

locals {
  oidc_provider_arn = var.create_oidc_provider ? aws_iam_openid_connect_provider.github[0].arn : data.aws_iam_openid_connect_provider.existing[0].arn
}

data "aws_iam_policy_document" "assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    effect  = "Allow"
    principals {
      type        = "Federated"
      identifiers = [local.oidc_provider_arn]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repo}:ref:refs/heads/${var.branch}"]
    }
  }
}

resource "aws_iam_role" "github_actions" {
  name               = "${var.name_prefix}-github-actions"
  assume_role_policy = data.aws_iam_policy_document.assume.json
  tags               = var.tags
}

data "aws_iam_policy_document" "permissions" {
  statement {
    sid       = "EcrAuth"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid = "EcrPush"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
      "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart",
      "ecr:CompleteLayerUpload",
      "ecr:PutImage",
    ]
    resources = var.ecr_repository_arns
  }

  statement {
    sid       = "EksDescribe"
    actions   = ["eks:DescribeCluster", "eks:ListClusters"]
    resources = [var.eks_cluster_arn]
  }
}

resource "aws_iam_role_policy" "permissions" {
  name   = "ci-permissions"
  role   = aws_iam_role.github_actions.id
  policy = data.aws_iam_policy_document.permissions.json
}

# kubectl/Helm access. Cluster-admin, matching the Azure tree's choice for the same reason:
# the deploy job installs ingress / ALB controller via Helm (cluster-scoped objects). Blast
# radius is bounded by the trust policy only trusting refs/heads/<branch>.
resource "aws_eks_access_entry" "github_actions" {
  cluster_name  = var.eks_cluster_name
  principal_arn = aws_iam_role.github_actions.arn
  type          = "STANDARD"
}

resource "aws_eks_access_policy_association" "github_actions_admin" {
  cluster_name  = var.eks_cluster_name
  principal_arn = aws_iam_role.github_actions.arn
  policy_arn    = "arn:${data.aws_partition.current.partition}:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy"

  access_scope {
    type = "cluster"
  }

  depends_on = [aws_eks_access_entry.github_actions]
}

output "github_actions_role_arn" {
  value = aws_iam_role.github_actions.arn
}
