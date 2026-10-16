/*
 * Amazon MSK — only when kafka_mode = "msk". SASL/SCRAM auth, one shared credential for all
 * services (matches the current no-per-topic-ACL posture; see the long note in
 * azure/envs/dev/eventhubs.tf). Topics are NOT created here (no AWS API): create them on
 * cutover with kafka-topics.sh or rely on auto.create.topics.enable.
 *
 * kafka_mode = "self-hosted" (default): this file provisions nothing — run Kafka in-cluster
 * like infra/k8s/overlays/aks/datastores/kafka.yaml (also home to RabbitMQ + Elasticsearch).
 */

locals {
  msk_enabled = var.kafka_mode == "msk"
}

resource "aws_kms_key" "msk_scram" {
  count                   = local.msk_enabled ? 1 : 0
  description             = "${var.name_prefix} MSK SASL/SCRAM secret encryption"
  deletion_window_in_days = 7
  tags                    = var.tags
}

resource "aws_security_group" "msk" {
  count       = local.msk_enabled ? 1 : 0
  name_prefix = "sg-${var.name_prefix}-msk-"
  description = "MSK brokers - reachable from EKS pods"
  vpc_id      = var.vpc_id

  ingress {
    description     = "EKS pods to Kafka (TLS + SASL_SSL)"
    from_port       = 9092
    to_port         = 9098
    protocol        = "tcp"
    security_groups = [var.cluster_security_group_id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.tags, { Name = "sg-${var.name_prefix}-msk" })

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_cloudwatch_log_group" "msk" {
  count             = local.msk_enabled ? 1 : 0
  name              = "/aws/msk/${var.name_prefix}"
  retention_in_days = var.cloudwatch_log_retention_days
  tags              = var.tags
}

resource "aws_msk_cluster" "main" {
  count                  = local.msk_enabled ? 1 : 0
  cluster_name           = "msk-${var.name_prefix}"
  kafka_version          = var.msk_kafka_version
  number_of_broker_nodes = var.msk_broker_count

  broker_node_group_info {
    instance_type   = var.msk_broker_instance_type
    client_subnets  = var.private_subnet_ids
    security_groups = [aws_security_group.msk[0].id]

    storage_info {
      ebs_storage_info {
        volume_size = var.msk_broker_ebs_gb
      }
    }
  }

  client_authentication {
    sasl {
      scram = true
    }
  }

  encryption_info {
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
  }

  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.msk[0].name
      }
    }
  }

  tags = var.tags
}

# Secrets Manager secret name MUST start with "AmazonMSK_" and use a customer-managed KMS key.
resource "aws_secretsmanager_secret" "msk_scram" {
  count                   = local.msk_enabled ? 1 : 0
  name                    = "AmazonMSK_${var.name_prefix}_platform"
  kms_key_id              = aws_kms_key.msk_scram[0].id
  recovery_window_in_days = 7
  tags                    = var.tags
}

resource "random_password" "msk_scram" {
  count   = local.msk_enabled ? 1 : 0
  length  = 32
  special = false
}

resource "aws_secretsmanager_secret_version" "msk_scram" {
  count     = local.msk_enabled ? 1 : 0
  secret_id = aws_secretsmanager_secret.msk_scram[0].id
  secret_string = jsonencode({
    username = "platform"
    password = random_password.msk_scram[0].result
  })
}

resource "aws_msk_scram_secret_association" "main" {
  count           = local.msk_enabled ? 1 : 0
  cluster_arn     = aws_msk_cluster.main[0].arn
  secret_arn_list = [aws_secretsmanager_secret.msk_scram[0].arn]

  depends_on = [aws_secretsmanager_secret_version.msk_scram]
}
