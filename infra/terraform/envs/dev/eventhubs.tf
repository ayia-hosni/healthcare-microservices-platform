/*
 * Kafka replacement: Event Hubs' Kafka-compatible endpoint (Standard tier+) lets the existing
 * Spring Kafka clients (spring.kafka.bootstrap-servers, security.protocol=SASL_SSL) connect
 * with only config changes, no code changes. One Event Hub per Kafka topic — see
 * backend/common/src/main/java/.../events/Topics.java, which this list mirrors.
 *
 * Auth model differs from today's docker-compose setup: today every one of the 9 services
 * authenticates with its own SASL/PLAIN username (see docker-compose.yml
 * KAFKA_LISTENER_NAME_BROKER_PLAIN_SASL_JAAS_CONFIG), but the broker has no ACL authorizer
 * configured, so any authenticated service can already produce/consume any topic — auth is
 * identity-only, not per-topic access control. Event Hubs' Kafka surface authenticates via a
 * SAS connection string where the username must literally be the string "$ConnectionString",
 * which doesn't support distinct per-service usernames. Given today's setup has no real
 * per-topic ACL to preserve, this uses a single Send+Listen (no Manage) namespace-level SAS
 * rule shared by all services, which matches the actual current security posture. Required
 * per-service application.yml changes on cutover:
 *   spring.kafka.bootstrap-servers: <namespace>.servicebus.windows.net:9093
 *   spring.kafka.properties.sasl.jaas.config: ...PlainLoginModule required username="$ConnectionString" password="<eventhub-connection-string from Key Vault>";
 * The ssl.truststore.* lines (and the kafka.truststore.p12 volume mount) can be dropped
 * entirely — Event Hubs presents a publicly-trusted CA cert, unlike the throwaway dev CA in
 * infra/docker/kafka/generate-dev-certs.sh, so the JVM's default truststore is sufficient.
 */

resource "azurerm_eventhub_namespace" "main" {
  name                = "evhns-${local.name_prefix}"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location

  sku      = var.eventhubs_sku
  capacity = var.eventhubs_capacity

  minimum_tls_version = "1.2"

  network_rulesets {
    default_action                 = "Deny"
    trusted_service_access_enabled = true

    ip_rule = concat(
      [{ ip_mask = "${azurerm_public_ip.nat_gateway.ip_address}/32", action = "Allow" }],
      [for c in var.admin_ip_cidrs : { ip_mask = c, action = "Allow" }]
    )
  }

  tags = local.tags
}

resource "azurerm_eventhub" "topic" {
  for_each = toset(var.kafka_topics)

  name              = each.value
  namespace_id      = azurerm_eventhub_namespace.main.id
  partition_count   = 2
  message_retention = 3
}

resource "azurerm_eventhub_namespace_authorization_rule" "app_services" {
  name                = "app-services"
  namespace_name      = azurerm_eventhub_namespace.main.name
  resource_group_name = azurerm_resource_group.main.name
  listen              = true
  send                = true
  manage              = false
}

resource "azurerm_key_vault_secret" "eventhub_connection_string" {
  name         = "eventhub-connection-string"
  value        = azurerm_eventhub_namespace_authorization_rule.app_services.primary_connection_string
  key_vault_id = azurerm_key_vault.main.id
  depends_on   = [azurerm_role_assignment.keyvault_admin_for_apply_identity]
}
