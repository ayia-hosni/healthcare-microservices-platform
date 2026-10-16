variable "project" {
  type    = string
  default = "healthcare-platform"
}

variable "environment" {
  type    = string
  default = "dev"
}

variable "tf_state_resource_group" {
  type    = string
  default = "rg-healthcare-platform-tfstate"
}

variable "tf_state_storage_account" {
  type    = string
  default = "sthealthplatformtfstate"
}

variable "tf_state_container" {
  type    = string
  default = "tfstate"
}

variable "github_repo" {
  type    = string
  default = "ayia-hosni/healthcare-microservices-platform"
}

variable "github_oidc_branch" {
  type    = string
  default = "main"
}
