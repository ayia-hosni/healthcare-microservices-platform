/*
 * GitHub Actions OIDC federation — CI authenticates to Azure with a short-lived OIDC token
 * (no stored client secret) to push images to ACR and deploy to AKS. Only main-branch pushes
 * get a credential.
 */

variable "name_prefix" {
  type = string
}

variable "github_repo" {
  description = "owner/repo"
  type        = string
}

variable "branch" {
  type    = string
  default = "main"
}

variable "acr_id" {
  type = string
}

variable "aks_cluster_id" {
  type = string
}

resource "azuread_application" "github_actions" {
  display_name = "${var.name_prefix}-github-actions"
}

resource "azuread_service_principal" "github_actions" {
  client_id = azuread_application.github_actions.client_id
}

resource "azuread_application_federated_identity_credential" "main" {
  application_id = azuread_application.github_actions.id
  display_name   = "github-actions-${var.branch}-push"
  audiences      = ["api://AzureADTokenExchange"]
  issuer         = "https://token.actions.githubusercontent.com"
  subject        = "repo:${var.github_repo}:ref:refs/heads/${var.branch}"
}

resource "azurerm_role_assignment" "acr_push" {
  scope                = var.acr_id
  role_definition_name = "AcrPush"
  principal_id         = azuread_service_principal.github_actions.object_id
}

# Cluster Admin (not namespace-scoped): the deploy job installs ingress-nginx via Helm, which
# creates cluster-scoped objects. Blast radius bounded by the federated credential only
# trusting refs/heads/<branch>.
resource "azurerm_role_assignment" "aks_admin" {
  scope                = var.aks_cluster_id
  role_definition_name = "Azure Kubernetes Service RBAC Cluster Admin"
  principal_id         = azuread_service_principal.github_actions.object_id
}

output "client_id" {
  value = azuread_application.github_actions.client_id
}
