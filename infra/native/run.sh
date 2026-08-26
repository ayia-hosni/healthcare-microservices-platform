#!/usr/bin/env bash
#
# Runs the entire platform as native processes — no Docker, no Kubernetes.
#
# macOS + Homebrew only. Starts PostgreSQL, Redis, RabbitMQ, and Kafka (KRaft mode, no
# Zookeeper needed on Kafka 4.x) as real local processes, creates the nine per-service
# databases, builds the backend once, then runs all nine domain services + graphql-gateway
# + the Angular frontend as background processes with logs under .native-run/logs/.
#
# Every default here matches what each service's application.yml already falls back to
# (DB_HOST=localhost, REDIS_HOST=localhost, etc. — see GETTING_STARTED.md's "Hard" tier).
# This script goes one step further than that tier: the infrastructure itself is native too,
# not Docker Compose.
#
# One override IS required and isn't optional: every domain service's application.yml
# hardcodes `security.protocol: SASL_SSL` for Kafka (needed for the Docker/Azure setups,
# which authenticate that way) with no fallback for KAFKA_SASL_USERNAME/KAFKA_SASL_PASSWORD.
# Against a plain local Kafka broker that has no SASL configured at all, we override
# security.protocol to PLAINTEXT and supply dummy SASL values just so the properties resolve
# at startup (see the KAFKA_ENV exports below) — Spring Boot env vars take precedence over
# application.yml, even for literal (non-placeholder) values.
#
# Usage:
#   ./infra/native/run.sh              # core stack: Postgres, Redis, RabbitMQ, Kafka, all services, frontend
#   ./infra/native/run.sh --full       # also start Elasticsearch and MinIO (optional — nothing
#                                       # core depends on them yet; MinIO is only used by
#                                       # emr-service's clinical-document upload, Elasticsearch
#                                       # has no indexing consumers wired up at all)
#   ./infra/native/run.sh --skip-kafka # don't start/require Kafka; services still start and
#                                       # log Kafka connection errors in the background rather
#                                       # than failing outright (Spring Kafka doesn't fail fast
#                                       # on a missing broker by default)
#
# Stop everything with ./infra/native/stop.sh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUN_DIR="$ROOT_DIR/.native-run"
LOG_DIR="$RUN_DIR/logs"
PID_DIR="$RUN_DIR/pids"
mkdir -p "$LOG_DIR" "$PID_DIR"

FULL=false
SKIP_KAFKA=false
for arg in "$@"; do
  case "$arg" in
    --full) FULL=true ;;
    --skip-kafka) SKIP_KAFKA=true ;;
    -h|--help)
      sed -n '2,29p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) echo "Unknown flag: $arg (see --help)"; exit 1 ;;
  esac
done

c_green="\033[0;32m"; c_yellow="\033[0;33m"; c_red="\033[0;31m"; c_reset="\033[0m"
info() { echo -e "${c_green}==>${c_reset} $*"; }
warn() { echo -e "${c_yellow}==>${c_reset} $*"; }
fail() { echo -e "${c_red}==>${c_reset} $*"; exit 1; }

command -v brew >/dev/null 2>&1 || fail "This script is macOS/Homebrew only. See GETTING_STARTED.md for other ways to run the platform."

# ---------------------------------------------------------------------------
# 1. Java 21 (the project targets Java 21 — see backend/pom.xml)
# ---------------------------------------------------------------------------
JAVA21_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
if [ -z "$JAVA21_HOME" ]; then
  fail "Java 21 not found. Install it with:\n    brew install --cask temurin21\n  then re-run this script."
fi
info "Using Java 21: $JAVA21_HOME"

command -v mvn  >/dev/null 2>&1 || fail "Maven not found. Install it with: brew install maven"
command -v psql >/dev/null 2>&1 || fail "psql not found. Install it with: brew install postgresql@17"
command -v node >/dev/null 2>&1 || fail "Node not found. Install it with: brew install node"

# ---------------------------------------------------------------------------
# 2. Infra via brew services — idempotent, asks before installing anything new,
#    never touches services it didn't start (see stop.sh).
# ---------------------------------------------------------------------------
ensure_brew_service() {
  local formula="$1"
  if ! brew list --formula "$formula" >/dev/null 2>&1; then
    read -r -p "$formula is not installed. Install it now with 'brew install $formula'? [y/N] " reply
    [[ "$reply" =~ ^[Yy]$ ]] || fail "$formula is required. Install it and re-run."
    brew install "$formula"
  fi
  brew services start "$formula" >/dev/null 2>&1 || true
}

info "Starting PostgreSQL, Redis, RabbitMQ..."
ensure_brew_service postgresql@17
ensure_brew_service redis
ensure_brew_service rabbitmq

if $FULL; then
  info "Starting Elasticsearch (--full)..."
  ensure_brew_service elasticsearch-full || warn "Elasticsearch didn't start cleanly — continuing without it."
fi

# ---------------------------------------------------------------------------
# 3. Create the nine per-service databases/roles. Non-destructive and
#    idempotent (mirrors infra/docker/init-multiple-dbs.sh) — safe to run
#    against a Postgres instance that already has other projects' databases
#    on it, which it never touches.
# ---------------------------------------------------------------------------
info "Ensuring per-service databases exist..."
DB_LIST="identity_db:identity_user:identity_pass
patient_db:patient_user:patient_pass
doctor_db:doctor_user:doctor_pass
appointment_db:appointment_user:appointment_pass
emr_db:emr_user:emr_pass
billing_db:billing_user:billing_pass
notification_db:notification_user:notification_pass
audit_db:audit_user:audit_pass
analytics_db:analytics_user:analytics_pass"

while IFS=: read -r db user pass; do
  [ -z "$db" ] && continue
  psql -v ON_ERROR_STOP=1 -d postgres <<-SQL
	DO \$\$
	BEGIN
		IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = '$user') THEN
			CREATE ROLE $user LOGIN PASSWORD '$pass';
		END IF;
	END
	\$\$;
	SELECT 'CREATE DATABASE $db OWNER $user' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '$db')\gexec
	GRANT ALL PRIVILEGES ON DATABASE $db TO $user;
	SQL
  psql -v ON_ERROR_STOP=1 -d "$db" -c "CREATE EXTENSION IF NOT EXISTS pgcrypto;" >/dev/null
done <<< "$DB_LIST"
info "Databases ready."

# ---------------------------------------------------------------------------
# 4. Kafka — KRaft mode (Kafka 4.x has no Zookeeper support at all, so this
#    is actually simpler than the Docker Compose setup). Formats storage on
#    first run only.
#
#    If brew's kafka formula ships a different config layout than expected
#    here, this step will fail with a clear message rather than silently
#    doing the wrong thing — re-run with --skip-kafka to proceed without it.
# ---------------------------------------------------------------------------
if ! $SKIP_KAFKA; then
  if ! brew list --formula kafka >/dev/null 2>&1; then
    read -r -p "Kafka is not installed. Install it now with 'brew install kafka'? [y/N] " reply
    [[ "$reply" =~ ^[Yy]$ ]] || fail "Kafka is required (or re-run with --skip-kafka). Install it and re-run."
    brew install kafka
  fi

  KAFKA_PREFIX="$(brew --prefix kafka)"
  BREW_ETC="$(brew --prefix)/etc"

  # Homebrew's kafka formula (4.x — KRaft only, no Zookeeper mode exists anymore) ships its
  # config under the *shared* Homebrew etc prefix, not under the per-formula opt path — i.e.
  # /usr/local/etc/kafka/server.properties, not /usr/local/opt/kafka/etc/kafka/.... That
  # server.properties is already pre-configured for KRaft standalone mode (process.roles=
  # broker,controller, PLAINTEXT on :9092) — there's no separate "kraft/" subdirectory to
  # look for. Still checking a couple of alternate layouts here in case that differs on
  # another machine/formula version.
  KAFKA_CONF=""
  for candidate in \
    "$BREW_ETC/kafka/server.properties" \
    "$KAFKA_PREFIX/etc/kafka/server.properties" \
    "$BREW_ETC/kafka/kraft/server.properties" \
    "$KAFKA_PREFIX/etc/kafka/kraft/server.properties"
  do
    if [ -f "$candidate" ]; then
      KAFKA_CONF="$candidate"
      break
    fi
  done
  KAFKA_MARKER="$RUN_DIR/.kafka-formatted"

  if [ -z "$KAFKA_CONF" ]; then
    fail "Couldn't find a Kafka config file. Checked:\n    $BREW_ETC/kafka/server.properties\n    $KAFKA_PREFIX/etc/kafka/server.properties\n  Run 'brew info kafka' and 'find $BREW_ETC/kafka $KAFKA_PREFIX -iname \"*.properties\"'\n  to see the real layout, then adjust this script — or re-run with --skip-kafka."
  fi
  info "Using Kafka config: $KAFKA_CONF"

  if [ ! -f "$KAFKA_MARKER" ]; then
    info "Formatting Kafka storage (first run only)..."
    CLUSTER_ID="$("$KAFKA_PREFIX/bin/kafka-storage" random-uuid)"
    "$KAFKA_PREFIX/bin/kafka-storage" format -t "$CLUSTER_ID" -c "$KAFKA_CONF" \
      --ignore-formatted >> "$LOG_DIR/kafka-format.log" 2>&1
    echo "$CLUSTER_ID" > "$KAFKA_MARKER"
  fi

  info "Starting Kafka (KRaft, PLAINTEXT, localhost:9092)..."
  nohup "$KAFKA_PREFIX/bin/kafka-server-start" "$KAFKA_CONF" \
    > "$LOG_DIR/kafka.log" 2>&1 &
  echo $! > "$PID_DIR/kafka.pid"

  info "Waiting for Kafka to accept connections..."
  for _ in $(seq 1 30); do
    nc -z localhost 9092 2>/dev/null && break
    sleep 1
  done
  nc -z localhost 9092 2>/dev/null || warn "Kafka doesn't seem to be listening yet — check $LOG_DIR/kafka.log"
else
  warn "Skipping Kafka (--skip-kafka). Services will still start; anything that touches Kafka will log connection errors."
fi

# ---------------------------------------------------------------------------
# MinIO (optional, --full only). emr-service creates its own bucket on
# startup, so a bare `minio server` with the default dev credentials is
# enough — no provisioning step needed here.
# ---------------------------------------------------------------------------
if $FULL; then
  if ! command -v minio >/dev/null 2>&1; then
    read -r -p "MinIO is not installed. Install it now with 'brew install minio'? [y/N] " reply
    [[ "$reply" =~ ^[Yy]$ ]] && brew install minio
  fi
  if command -v minio >/dev/null 2>&1; then
    info "Starting MinIO (localhost:9000, console :9001)..."
    (
      export MINIO_ROOT_USER=minioadmin
      export MINIO_ROOT_PASSWORD=minioadmin
      nohup minio server "$RUN_DIR/minio-data" --address ":9000" --console-address ":9001" \
        > "$LOG_DIR/minio.log" 2>&1 &
      echo $! > "$PID_DIR/minio.pid"
    )
  else
    warn "MinIO not installed — skipping (emr-service's document upload won't work without it)."
  fi
fi

# ---------------------------------------------------------------------------
# 5. Build once (installs `common`/`grpc-contracts` into the local Maven repo
#    so the other modules can resolve them).
# ---------------------------------------------------------------------------
info "Building the backend (mvn clean install -DskipTests — first run takes a while)..."
( cd "$ROOT_DIR/backend" && JAVA_HOME="$JAVA21_HOME" mvn -q clean install -DskipTests )

# ---------------------------------------------------------------------------
# 6. Start the nine domain services + graphql-gateway.
# ---------------------------------------------------------------------------
SERVICES=(identity-service patient-service doctor-service appointment-service emr-service
          billing-service notification-service audit-service analytics-service graphql-gateway)

for svc in "${SERVICES[@]}"; do
  info "Starting $svc..."
  (
    cd "$ROOT_DIR/backend/$svc"
    export JAVA_HOME="$JAVA21_HOME"
    export SPRING_KAFKA_PROPERTIES_SECURITY_PROTOCOL=PLAINTEXT
    export KAFKA_SASL_USERNAME=local
    export KAFKA_SASL_PASSWORD=local
    nohup mvn -q spring-boot:run > "$LOG_DIR/$svc.log" 2>&1 &
    echo $! > "$PID_DIR/$svc.pid"
  )
done

# ---------------------------------------------------------------------------
# 7. Frontend — not part of any compose/k8s deployment either; always native.
# ---------------------------------------------------------------------------
info "Starting the frontend..."
(
  cd "$ROOT_DIR/frontend"
  [ -d node_modules ] || npm install
  nohup npm start > "$LOG_DIR/frontend.log" 2>&1 &
  echo $! > "$PID_DIR/frontend.pid"
)

echo
info "Everything is starting up in the background — the backend services in particular take"
info "a minute or two to finish booting (Flyway migrations, Kafka listener startup, etc.)."
echo "  Logs:          $LOG_DIR/<service>.log"
echo "  One endpoint:  http://localhost:4200  — ng serve's own dev-server proxy"
echo "                 (frontend/proxy.conf.json) fronts the frontend + /api/v1/{auth,"
echo "                 patients,doctors,appointments,invoices,billing} + /graphql,/graphiql,"
echo "                 same-origin, mirroring infra/k8s/base/ingress.yaml (ADR-0001)."
echo "  Direct ports:  http://localhost:8081-8090  — one per backend service, for bypassing"
echo "                 the proxy or hitting emr/notification/audit/analytics directly"
echo "                 (those four have no route through :4200, matching the k8s Ingress)."
echo "  Health check:  curl http://localhost:8081/actuator/health  (repeat per port 8081-8090)"
echo "  Stop:          ./infra/native/stop.sh"
