/*
 * IRSA roles. Trust the cluster OIDC provider scoped to one ServiceAccount each.
 * Permission policies use deterministic ARN patterns (not resource refs) so this module
 * never depends on the data/storage layers.
 */

# --- Workload identity (app pods) --------------------------------------
data "aws_iam_policy_document" "workload_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    effect  = "Allow"
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.eks.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "${local.oidc_host}:aud"
      values   = ["sts.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "${local.oidc_host}:sub"
      values   = ["system:serviceaccount:${var.workload_namespace}:${var.workload_service_account}"]
    }
  }
}

resource "aws_iam_role" "workload" {
  name               = "${var.name_prefix}-workload"
  assume_role_policy = data.aws_iam_policy_document.workload_assume.json
  tags               = var.tags
}

data "aws_iam_policy_document" "workload" {
  statement {
    sid     = "ReadPlatformSecrets"
    actions = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
    resources = [
      "arn:${local.partition}:secretsmanager:${local.region}:${local.account_id}:secret:${var.secretsmanager_path_prefix}*",
      "arn:${local.partition}:secretsmanager:${local.region}:${local.account_id}:secret:AmazonMSK_${var.name_prefix}_*",
    ]
  }

  statement {
    sid       = "EmrAttachmentsBucketList"
    actions   = ["s3:ListBucket"]
    resources = [var.emr_bucket_arn]
  }

  statement {
    sid       = "EmrAttachmentsObjectRW"
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = ["${var.emr_bucket_arn}/*"]
  }

  # Decrypt SSE-KMS for S3 and Secrets Manager (scoped by the calling service, not key ARN).
  statement {
    sid       = "KmsDecryptViaService"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey"]
    resources = ["*"]
    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values = [
        "s3.${local.region}.amazonaws.com",
        "secretsmanager.${local.region}.amazonaws.com",
      ]
    }
  }
}

resource "aws_iam_role_policy" "workload" {
  name   = "platform-permissions"
  role   = aws_iam_role.workload.id
  policy = data.aws_iam_policy_document.workload.json
}

# --- EBS CSI driver -----------------------------------------------
data "aws_iam_policy_document" "ebs_csi_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    effect  = "Allow"
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.eks.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "${local.oidc_host}:sub"
      values   = ["system:serviceaccount:kube-system:ebs-csi-controller-sa"]
    }
    condition {
      test     = "StringEquals"
      variable = "${local.oidc_host}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "ebs_csi" {
  name               = "${var.name_prefix}-ebs-csi"
  assume_role_policy = data.aws_iam_policy_document.ebs_csi_assume.json
  tags               = var.tags
}

resource "aws_iam_role_policy_attachment" "ebs_csi" {
  role       = aws_iam_role.ebs_csi.name
  policy_arn = "arn:${local.partition}:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy"
}

# --- CloudWatch agent / Container Insights -----------------------
data "aws_iam_policy_document" "container_insights_assume" {
  count = var.enable_container_insights ? 1 : 0
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    effect  = "Allow"
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.eks.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "${local.oidc_host}:sub"
      values   = ["system:serviceaccount:amazon-cloudwatch:cloudwatch-agent"]
    }
    condition {
      test     = "StringEquals"
      variable = "${local.oidc_host}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "container_insights" {
  count              = var.enable_container_insights ? 1 : 0
  name               = "${var.name_prefix}-container-insights"
  assume_role_policy = data.aws_iam_policy_document.container_insights_assume[0].json
  tags               = var.tags
}

resource "aws_iam_role_policy_attachment" "container_insights" {
  count      = var.enable_container_insights ? 1 : 0
  role       = aws_iam_role.container_insights[0].name
  policy_arn = "arn:${local.partition}:iam::aws:policy/CloudWatchAgentServerPolicy"
}

# --- AWS Load Balancer Controller (Helm-installed at app-deploy time) --
data "aws_iam_policy_document" "alb_controller_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    effect  = "Allow"
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.eks.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "${local.oidc_host}:sub"
      values   = ["system:serviceaccount:kube-system:aws-load-balancer-controller"]
    }
    condition {
      test     = "StringEquals"
      variable = "${local.oidc_host}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "alb_controller" {
  name               = "${var.name_prefix}-alb-controller"
  assume_role_policy = data.aws_iam_policy_document.alb_controller_assume.json
  tags               = var.tags
}

resource "aws_iam_policy" "alb_controller" {
  name        = "${var.name_prefix}-alb-controller"
  description = "AWS Load Balancer Controller - upstream policy (kubernetes-sigs/aws-load-balancer-controller v2.9.2)"
  policy      = file("${path.module}/policies/aws-load-balancer-controller.json")
}

resource "aws_iam_role_policy_attachment" "alb_controller" {
  role       = aws_iam_role.alb_controller.name
  policy_arn = aws_iam_policy.alb_controller.arn
}
