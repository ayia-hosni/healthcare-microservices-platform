/*
 * One ECR repository per deployable image. CI (the cicd module) pushes; EKS nodes pull via
 * the AmazonEC2ContainerRegistryReadOnly policy on the node role.
 */

variable "name_prefix" {
  type = string
}

variable "tags" {
  type    = map(string)
  default = {}
}

variable "service_images" {
  type = list(string)
}

variable "image_retention_count" {
  type    = number
  default = 15
}

data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

resource "aws_ecr_repository" "service" {
  for_each = toset(var.service_images)

  name                 = "${var.name_prefix}/${each.value}"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = true # POC: allow `terraform destroy` to drop repos that still hold images

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }

  tags = var.tags
}

resource "aws_ecr_lifecycle_policy" "service" {
  for_each   = aws_ecr_repository.service
  repository = each.value.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Expire untagged images after 7 days"
        selection    = { tagStatus = "untagged", countType = "sinceImagePushed", countUnit = "days", countNumber = 7 }
        action       = { type = "expire" }
      },
      {
        rulePriority = 2
        description  = "Keep only the most recent ${var.image_retention_count} tagged images"
        selection    = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = var.image_retention_count }
        action       = { type = "expire" }
      },
    ]
  })
}

output "repository_urls" {
  value = { for k, r in aws_ecr_repository.service : k => r.repository_url }
}

output "repository_arns" {
  value = [for r in aws_ecr_repository.service : r.arn]
}

output "registry" {
  value = "${data.aws_caller_identity.current.account_id}.dkr.ecr.${data.aws_region.current.name}.amazonaws.com"
}
