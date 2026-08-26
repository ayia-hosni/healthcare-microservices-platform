#!/usr/bin/env bash
#
# Stops everything started by run.sh: the nine domain services, graphql-gateway, the
# frontend, and (if it was started) the native Kafka process.
#
# Postgres, Redis, RabbitMQ, and Elasticsearch are left running by default — they're managed
# as shared brew services and may be in use by other projects on this machine. Pass --all to
# stop those too.
#
# Usage:
#   ./infra/native/stop.sh          # stop this project's processes only
#   ./infra/native/stop.sh --all    # also stop the shared brew services (Postgres/Redis/RabbitMQ/Elasticsearch)

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUN_DIR="$ROOT_DIR/.native-run"
PID_DIR="$RUN_DIR/pids"

STOP_INFRA=false
for arg in "$@"; do
  [ "$arg" = "--all" ] && STOP_INFRA=true
done

if [ -d "$PID_DIR" ]; then
  for pidfile in "$PID_DIR"/*.pid; do
    [ -e "$pidfile" ] || continue
    name="$(basename "$pidfile" .pid)"
    pid="$(cat "$pidfile")"
    if kill -0 "$pid" 2>/dev/null; then
      echo "Stopping $name (pid $pid)..."
      kill "$pid" 2>/dev/null || true
    fi
    rm -f "$pidfile"
  done
fi

# `mvn spring-boot:run` forks a child JVM; killing the mvn parent above usually takes it down
# too, but clean up anything still bound to this project's known ports just in case.
for port in 8081 8082 8083 8084 8085 8086 8087 8088 8089 8090 9092 9000 9001; do
  pid="$(lsof -ti tcp:"$port" 2>/dev/null || true)"
  [ -n "$pid" ] && kill "$pid" 2>/dev/null || true
done

if $STOP_INFRA; then
  echo "Stopping shared brew services (postgresql@17, redis, rabbitmq, elasticsearch-full)..."
  echo "These may be used by other projects on this machine — only stopping because --all was passed."
  brew services stop postgresql@17    >/dev/null 2>&1 || true
  brew services stop redis            >/dev/null 2>&1 || true
  brew services stop rabbitmq         >/dev/null 2>&1 || true
  brew services stop elasticsearch-full >/dev/null 2>&1 || true
else
  echo "PostgreSQL/Redis/RabbitMQ are left running (shared brew services). Pass --all to stop them too."
fi

echo "Done."
