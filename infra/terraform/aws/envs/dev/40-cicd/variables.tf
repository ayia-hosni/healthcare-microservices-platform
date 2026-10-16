variable "project" {
  type    = string
  default = "healthcare-platform"
}

variable "environment" {
  type    = string
  default = "dev"
}

variable "region" {
  type    = string
  default = "us-east-1"
}

variable "tags" {
  type = map(string)
  default = {
    project   = "healthcare-platform"
    managedby = "terraform"
  }
}

variable "tf_state_bucket" {
  type    = string
  default = "healthcare-platform-tfstate"
}

variable "tf_state_region" {
  type    = string
  default = "us-east-1"
}

variable "github_repo" {
  description = "owner/repo that CI runs from."
  type        = string
  default     = "ayia-hosni/healthcare-microservices-platform"
}

variable "github_oidc_branch" {
  type    = string
  default = "main"
}

variable "create_github_oidc_provider" {
  description = "false if token.actions.githubusercontent.com is already an IAM OIDC provider in this account."
  type        = bool
  default     = true
}
