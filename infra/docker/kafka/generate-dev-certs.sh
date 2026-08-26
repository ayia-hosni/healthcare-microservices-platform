#!/usr/bin/env bash
# Generates a throwaway self-signed keystore + truststore for running Kafka over SASL_SSL in
# docker-compose. Dev-only: real deployments get certs from an actual CA / cert-manager, not
# a script that prints "changeit" as the store password.
#
# Deliberately the simplest possible shape: one self-signed cert, used directly as both the
# broker's identity (keystore) and the sole trusted entry (truststore) — no separate CA/CSR
# signing chain. A CA+CSR chain was tried first and produced hard-to-diagnose TLS negotiation
# failures (SSLHandshakeException: "no available authentication scheme", then "no cipher
# suites in common") in the broker's internal SslFactory self-test; this single-cert pattern
# is the standard, well-tested shape used in virtually every Kafka-SSL-in-docker-compose
# example for exactly that reason.
#
# Idempotent: skips generation if the certs directory already has output, unless -f is passed.
#
# PKCS12_COMPAT_OPTS below is a second, unrelated fix for the exact same symptom
# ("SSLHandshakeException: no available authentication scheme" in the broker's own
# SslFactory self-test): `keytool` from a JDK 17+ host defaults new PKCS12 stores to
# PBEWithHmacSHA256AndAES_256 encryption, which the cp-kafka image's older bundled JRE
# (Java 11) cannot read back — the broker fails before it ever gets to negotiate TLS with
# a client. Forcing the legacy PBEWithSHA1AndDESede algorithm keeps the generated store
# readable by any JDK likely to run this broker image, not just whichever JDK happens to
# be on the host running this script.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CERTS_DIR="$SCRIPT_DIR/certs"
STORE_PASS="changeit"
DAYS=3650
FORCE=false
PKCS12_COMPAT_OPTS=(
  -J-Dkeystore.pkcs12.certProtectionAlgorithm=PBEWithSHA1AndDESede
  -J-Dkeystore.pkcs12.keyProtectionAlgorithm=PBEWithSHA1AndDESede
  -J-Dkeystore.pkcs12.macAlgorithm=HmacPBESHA1
)

if [[ "${1:-}" == "-f" ]]; then
  FORCE=true
fi

if [[ -f "$CERTS_DIR/kafka.truststore.p12" && "$FORCE" != "true" ]]; then
  echo "Certs already exist in $CERTS_DIR (use -f to regenerate). Skipping."
  exit 0
fi

rm -rf "$CERTS_DIR"
mkdir -p "$CERTS_DIR"
cd "$CERTS_DIR"

echo "==> Generating self-signed broker keypair"
keytool -genkeypair -alias kafka-broker -keystore kafka.broker.keystore.p12 \
  -storetype PKCS12 -keyalg RSA -keysize 2048 -sigalg SHA256withRSA -validity "$DAYS" \
  -storepass "$STORE_PASS" -keypass "$STORE_PASS" \
  -dname "CN=kafka,OU=dev,O=healthcare-platform" \
  -ext SAN=dns:kafka,dns:localhost \
  -ext KeyUsage=digitalSignature,keyEncipherment,keyCertSign \
  -ext ExtendedKeyUsage=serverAuth,clientAuth \
  "${PKCS12_COMPAT_OPTS[@]}"

echo "==> Exporting the broker's own cert"
keytool -exportcert -alias kafka-broker -keystore kafka.broker.keystore.p12 \
  -storetype PKCS12 -storepass "$STORE_PASS" -file kafka-broker.cert

echo "==> Building truststore that trusts exactly that cert"
keytool -importcert -alias kafka-broker -keystore kafka.truststore.p12 \
  -storetype PKCS12 -storepass "$STORE_PASS" -file kafka-broker.cert -noprompt \
  "${PKCS12_COMPAT_OPTS[@]}"

rm -f kafka-broker.cert

# The Confluent images read keystore/key/truststore passwords from files, not env vars
# directly (KAFKA_SSL_*_CREDENTIALS points at a filename under the same secrets mount).
echo -n "$STORE_PASS" > kafka_keystore_creds
echo -n "$STORE_PASS" > kafka_key_creds
echo -n "$STORE_PASS" > kafka_truststore_creds

echo "==> Done. Generated in $CERTS_DIR:"
ls -1 "$CERTS_DIR"
