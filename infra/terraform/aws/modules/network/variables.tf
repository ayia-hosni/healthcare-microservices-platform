variable "name_prefix" {
  description = "e.g. healthcare-platform-dev — all resource names derive from this."
  type        = string
}

variable "tags" {
  type    = map(string)
  default = {}
}

variable "vpc_cidr" {
  type    = string
  default = "10.20.0.0/16"
}

variable "azs" {
  description = "Availability-zone names to spread subnets across (>= 2 for EKS)."
  type        = list(string)
}

variable "single_nat_gateway" {
  description = "true = one shared NAT Gateway (POC). false = one per AZ (HA, higher cost)."
  type        = bool
  default     = true
}

variable "admin_ip_cidrs" {
  description = "CIDRs allowed to reach Postgres (5432) directly on the shared data security group."
  type        = list(string)
  default     = []
}
