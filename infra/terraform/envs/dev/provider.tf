provider "azurerm" {
  features {
    key_vault {
      purge_soft_delete_on_destroy    = false
      recover_soft_deleted_key_vaults = true
    }
    resource_group {
      prevent_deletion_if_contains_resources = true
    }
  }
}

# Authenticates as the Postgres server admin (see postgres.tf) to create one login role +
# database per microservice, mirroring infra/docker/init-multiple-dbs.sh. Requires the
# machine running `terraform apply` to be able to reach the server on 5432 — see the firewall
# rules in postgres.tf and the note in README.md about tightening this to VNet-only access.
provider "postgresql" {
  host            = azurerm_postgresql_flexible_server.main.fqdn
  port            = 5432
  username        = var.postgres_admin_username
  password        = random_password.postgres_admin.result
  sslmode         = "require"
  superuser       = false
  connect_timeout = 30
}
