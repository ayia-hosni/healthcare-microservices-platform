# ---------------------------------------------------------------------------
# GitHub Actions OIDC federation — lets .github/workflows/ci.yml authenticate to
# Azure with a short-lived OIDC token instead of a stored client secret, to push
# images to ACR and deploy to AKS. This is a distinct federation trust from
# azurerm_federated_identity_credential.workload in aks.tf, which federates a
# managed identity to *this cluster's own* OIDC issuer for in-cluster pods —
# unrelated to GitHub's OIDC issuer used here.
# ---------------------------------------------------------------------------

resource "azuread_application" "github_actions" {
  display_name = "${local.name_prefix}-github-actions"
}

resource "azuread_service_principal" "github_actions" {
  client_id = azuread_application.github_actions.client_id
}

# Only main-branch pushes get a credential — PRs authenticate to nothing and the
# workflow's docker-build-push/deploy jobs simply don't run for them (see ci.yml).
resource "azuread_application_federated_identity_credential" "github_actions_main" {
  application_id = azuread_application.github_actions.id
  display_name   = "github-actions-main-push"
  audiences      = ["api://AzureADTokenExchange"]
  issuer         = "https://token.actions.githubusercontent.com"
  subject        = "repo:ayia-hosni/healthcare-microservices-platform:ref:refs/heads/main"
}

# Push images built in CI to ACR.
resource "azurerm_role_assignment" "github_actions_acr_push" {
  scope                = azurerm_container_registry.main.id
  role_definition_name = "AcrPush"
  principal_id         = azuread_service_principal.github_actions.object_id
}

# kubectl/Helm access to the Azure-RBAC-only cluster (local_account_disabled = true in aks.tf).
# Cluster Admin, not a namespace-scoped Writer/Admin: the deploy job installs ingress-nginx via
# Helm, which creates cluster-scoped objects (the ingress-nginx Namespace, ClusterRole,
# ClusterRoleBinding, IngressClass, ValidatingWebhookConfiguration) that namespace-scoped AKS
# RBAC roles can't create regardless of scope. Mirrors aks_admin_for_apply_identity's existing
# choice of the same role for the same reason. Blast radius is bounded by the federated
# credential above only trusting refs/heads/main — PR workflows get no Azure credential at all.
resource "azurerm_role_assignment" "github_actions_aks_admin" {
  scope                = azurerm_kubernetes_cluster.main.id
  role_definition_name = "Azure Kubernetes Service RBAC Cluster Admin"
  principal_id         = azuread_service_principal.github_actions.object_id
}
