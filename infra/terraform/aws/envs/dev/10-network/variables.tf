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

variable "vpc_cidr" {
  type    = string
  default = "10.20.0.0/16"
}

variable "az_count" {
  type    = number
  default = 2
}

variable "single_nat_gateway" {
  description = "true = one shared NAT Gateway (POC). false = one per AZ (HA, higher cost)."
  type        = bool
  default     = true
}

variable "admin_ip_cidrs" {
  description = "Your workstation/office/VPN egress /32s — allowed to reach Postgres directly (terraform apply / psql)."
  type        = list(string)
}
