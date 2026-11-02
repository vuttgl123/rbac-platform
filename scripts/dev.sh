#!/usr/bin/env bash
# One-command dev stack: one embedded Postgres shared by identity, authz and audit, plus the Vite frontend.
# Usage: scripts/dev.sh [--no-install] [--no-fe]
# Ctrl+C stops everything, including Postgres.
set -uo pipefail

cd "$(dirname "$0")/.."

INSTALL=1
FE=1
for arg in "$@"; do
  case "$arg" in
    --no-install) INSTALL=0 ;;
    --no-fe) FE=0 ;;
    -h|--help) sed -n '2,4p' "$0"; exit 0 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

if [ "$INSTALL" = 1 ]; then
  ./mvnw -q install -DskipTests || exit 1
fi

# Exported, so they outrank anything in .env (for example Supabase values).
export DB_URL="jdbc:postgresql://localhost:${DEV_DB_PORT:-54329}/postgres"
export IDENTITY_DB_USER=identity_app AUTHZ_DB_USER=authz_app AUDIT_DB_USER=audit_app
export IDENTITY_DB_PASSWORD=unused AUTHZ_DB_PASSWORD=unused AUDIT_DB_PASSWORD=unused
export IDENTITY_PUBLIC_URL=http://localhost:8081
export AUTHZ_URL=http://localhost:8082
if [ -z "${RBAC_INTERNAL_TOKEN:-}" ]; then
  if command -v openssl >/dev/null 2>&1; then
    RBAC_INTERNAL_TOKEN=$(openssl rand -hex 32)
  else
    RBAC_INTERNAL_TOKEN=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')
  fi
fi
export RBAC_INTERNAL_TOKEN

LOG_TMP=$(mktemp -d)
PGIDS=()

cleanup() {
  trap - EXIT INT TERM
  echo
  echo "Stopping dev stack..."
  # Reverse start order: frontend and services first, database last.
  for ((i = ${#PGIDS[@]} - 1; i >= 0; i--)); do
    kill -TERM -- "-${PGIDS[$i]}" 2>/dev/null
  done
  for pgid in "${PGIDS[@]}"; do
    for _ in $(seq 1 100); do
      kill -0 -- "-$pgid" 2>/dev/null || break
      sleep 0.3
    done
    kill -KILL -- "-$pgid" 2>/dev/null
  done
  rm -rf "$LOG_TMP"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# start <name> <command...>: own session/process group, output prefixed and kept in a log for the wait loops.
start() {
  local name=$1; shift
  setsid bash -c '
    name=$1; log=$2; shift 2
    "$@" 2>&1 | tee "$log" | sed -u "s/^/[$name] /"
  ' _ "$name" "$LOG_TMP/$name.log" "$@" &
  PGIDS+=("$!")
}

wait_for() { # <description> <timeout seconds> <command...>
  local what=$1 limit=$2; shift 2
  local end=$((SECONDS + limit))
  until "$@"; do
    if [ "$SECONDS" -ge "$end" ]; then
      echo "Timed out after ${limit}s waiting for $what" >&2
      exit 1
    fi
    sleep 1
  done
}

echo "Starting embedded Postgres ($DB_URL)..."
start db ./mvnw -q -pl test-support exec:java
wait_for "DEV DB READY" 120 grep -q 'DEV DB READY' "$LOG_TMP/db.log"

for svc in identity authz audit; do
  start "$svc" ./mvnw -q -pl "services/$svc-service" spring-boot:run
done
for port in 8081 8082 8083; do
  wait_for "health on :$port" 180 curl -sf "localhost:$port/actuator/health" -o /dev/null
done

if [ "$FE" = 1 ]; then
  start fe npm --prefix frontend run dev
fi

cat <<TABLE

Dev stack is up (all three services share one database):
  DB        $DB_URL
  identity  http://localhost:8081
  authz     http://localhost:8082
  audit     http://localhost:8083
$( [ "$FE" = 1 ] && echo "  frontend  http://localhost:5173 (proxies /api/identity, /api/authz, /api/audit)" )
Ctrl+C stops everything.
TABLE

# Stay up while any child group is alive (wait returns as soon as one exits).
wait
