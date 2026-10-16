/*
 * EKS cluster + managed node groups + IRSA roles. The platform layer's core.
 * Boundary (same as the Azure tree): stops at "cluster ready for `helm upgrade --install`".
 */

data "aws_partition" "current" {}
data "aws_region" "current" {}
data "aws_caller_identity" "current" {}

locals {
  partition    = data.aws_partition.current.partition
  region       = data.aws_region.current.name
  account_id   = data.aws_caller_identity.current.account_id
  cluster_name = "eks-${var.name_prefix}"

  oidc_host = replace(aws_eks_cluster.main.identity[0].oidc[0].issuer, "https://", "")
}

# --- Cluster IAM role ------------------------------------------------------
data "aws_iam_policy_document" "eks_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["eks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "cluster" {
  name               = "${var.name_prefix}-eks-cluster"
  assume_role_policy = data.aws_iam_policy_document.eks_assume.json
  tags               = var.tags
}

resource "aws_iam_role_policy_attachment" "cluster" {
  role       = aws_iam_role.cluster.name
  policy_arn = "arn:${local.partition}:iam::aws:policy/AmazonEKSClusterPolicy"
}

# --- Control-plane logging (name is literal to stay a dependency, not a cycle) -----------
resource "aws_cloudwatch_log_group" "cluster" {
  name              = "/aws/eks/eks-${var.name_prefix}/cluster"
  retention_in_days = var.cloudwatch_log_retention_days
  tags              = var.tags
}

# --- Cluster --------------------------------------------------------------
resource "aws_eks_cluster" "main" {
  name     = local.cluster_name
  version  = var.kubernetes_version
  role_arn = aws_iam_role.cluster.arn

  enabled_cluster_log_types = ["api", "audit", "authenticator"]

  access_config {
    authentication_mode                         = "API_AND_CONFIG_MAP"
    bootstrap_cluster_creator_admin_permissions = true
  }

  vpc_config {
    subnet_ids              = concat(var.private_subnet_ids, var.public_subnet_ids)
    endpoint_private_access = true
    endpoint_public_access  = var.endpoint_public_access
    public_access_cidrs     = var.endpoint_public_access ? var.public_access_cidrs : null
  }

  tags = var.tags

  lifecycle {
    ignore_changes = [version]
  }

  depends_on = [
    aws_iam_role_policy_attachment.cluster,
    aws_cloudwatch_log_group.cluster,
  ]
}

# --- OIDC provider (IRSA) ------------------------------------------------
data "tls_certificate" "oidc" {
  url = aws_eks_cluster.main.identity[0].oidc[0].issuer
}

resource "aws_iam_openid_connect_provider" "eks" {
  url             = aws_eks_cluster.main.identity[0].oidc[0].issuer
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = [data.tls_certificate.oidc.certificates[0].sha1_fingerprint]
  tags            = var.tags
}

# --- Node IAM role -----------------------------------------------------
data "aws_iam_policy_document" "node_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "node" {
  name               = "${var.name_prefix}-eks-node"
  assume_role_policy = data.aws_iam_policy_document.node_assume.json
  tags               = var.tags
}

resource "aws_iam_role_policy_attachment" "node" {
  for_each = toset([
    "AmazonEKSWorkerNodePolicy",
    "AmazonEKS_CNI_Policy",
    "AmazonEC2ContainerRegistryReadOnly",
    "AmazonSSMManagedInstanceCore",
  ])
  role       = aws_iam_role.node.name
  policy_arn = "arn:${local.partition}:iam::aws:policy/${each.value}"
}

# --- Node groups -----------------------------------------------------
resource "aws_eks_node_group" "system" {
  cluster_name    = aws_eks_cluster.main.name
  node_group_name = "system"
  node_role_arn   = aws_iam_role.node.arn
  subnet_ids      = var.private_subnet_ids
  instance_types  = var.system_node_instance_types
  capacity_type   = "ON_DEMAND"

  scaling_config {
    desired_size = var.system_node_desired_size
    min_size     = var.system_node_min_size
    max_size     = var.system_node_max_size
  }

  update_config {
    max_unavailable = 1
  }

  labels = { workload = "system" }
  tags   = var.tags

  lifecycle {
    ignore_changes = [scaling_config[0].desired_size]
  }

  depends_on = [aws_iam_role_policy_attachment.node]
}

resource "aws_eks_node_group" "apps" {
  count = var.app_node_min_size > 0 ? 1 : 0

  cluster_name    = aws_eks_cluster.main.name
  node_group_name = "apps"
  node_role_arn   = aws_iam_role.node.arn
  subnet_ids      = var.private_subnet_ids
  instance_types  = var.app_node_instance_types
  capacity_type   = var.app_node_capacity_type

  scaling_config {
    desired_size = var.app_node_desired_size
    min_size     = var.app_node_min_size
    max_size     = var.app_node_max_size
  }

  update_config {
    max_unavailable = 1
  }

  labels = { workload = "app" }
  tags   = var.tags

  lifecycle {
    ignore_changes = [scaling_config[0].desired_size]
  }

  depends_on = [aws_iam_role_policy_attachment.node]
}

# --- Add-ons --------------------------------------------------------
resource "aws_eks_addon" "core" {
  for_each = toset(["vpc-cni", "kube-proxy", "coredns"])

  cluster_name                = aws_eks_cluster.main.name
  addon_name                  = each.value
  resolve_conflicts_on_create = "OVERWRITE"
  resolve_conflicts_on_update = "OVERWRITE"
  tags                        = var.tags

  depends_on = [aws_eks_node_group.system]
}

resource "aws_eks_addon" "ebs_csi" {
  cluster_name                = aws_eks_cluster.main.name
  addon_name                  = "aws-ebs-csi-driver"
  service_account_role_arn    = aws_iam_role.ebs_csi.arn
  resolve_conflicts_on_create = "OVERWRITE"
  resolve_conflicts_on_update = "OVERWRITE"
  tags                        = var.tags

  depends_on = [aws_eks_node_group.system]
}

resource "aws_eks_addon" "container_insights" {
  count = var.enable_container_insights ? 1 : 0

  cluster_name                = aws_eks_cluster.main.name
  addon_name                  = "amazon-cloudwatch-observability"
  service_account_role_arn    = aws_iam_role.container_insights[0].arn
  resolve_conflicts_on_create = "OVERWRITE"
  resolve_conflicts_on_update = "OVERWRITE"
  tags                        = var.tags

  depends_on = [aws_eks_node_group.system]
}

# Fluent Bit / the CW agent create these on first write with NO retention — pre-create to pin it.
resource "aws_cloudwatch_log_group" "container_insights" {
  for_each = var.enable_container_insights ? toset(["application", "dataplane", "host", "performance"]) : toset([])

  name              = "/aws/containerinsights/eks-${var.name_prefix}/${each.value}"
  retention_in_days = var.cloudwatch_log_retention_days
  tags              = var.tags
}

# --- Access entries -------------------------------------------------
resource "aws_eks_access_entry" "admin" {
  for_each      = toset(var.cluster_admin_principal_arns)
  cluster_name  = aws_eks_cluster.main.name
  principal_arn = each.value
  type          = "STANDARD"
}

resource "aws_eks_access_policy_association" "admin" {
  for_each      = toset(var.cluster_admin_principal_arns)
  cluster_name  = aws_eks_cluster.main.name
  principal_arn = each.value
  policy_arn    = "arn:${local.partition}:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy"

  access_scope {
    type = "cluster"
  }

  depends_on = [aws_eks_access_entry.admin]
}
