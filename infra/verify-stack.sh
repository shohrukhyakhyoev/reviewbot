#!/usr/bin/env bash
# Smoke test for the local compose stack. Exits 0 only if every service answers.
# Usage: infra/verify-stack.sh   (from any directory)
set -u

# Git Bash (Windows) rewrites container paths like /opt/kafka/... into host paths
# before docker sees them. No effect on Linux/macOS.
export MSYS_NO_PATHCONV=1

# cd instead of passing -f <path>: with path conversion off, a POSIX-style path
# would reach docker.exe unresolved on Windows.
cd "$(dirname "${BASH_SOURCE[0]}")" || exit 1
COMPOSE=(docker compose)
WIREMOCK_URL="${WIREMOCK_URL:-http://localhost:8089}"

failed=0

check() {
  local name="$1"
  shift
  if "$@" >/dev/null 2>&1; then
    echo "OK    $name"
  else
    echo "FAIL  $name"
    failed=1
  fi
}

# One-shot init services (kafka-init) may still be running when `up --wait` returns: a
# container without a healthcheck counts as ready the moment it starts. Retry for up to 30 s.
check_eventually() {
  local name="$1" i
  shift
  for i in $(seq 1 15); do
    if "$@" >/dev/null 2>&1; then
      echo "OK    $name"
      return
    fi
    sleep 2
  done
  echo "FAIL  $name"
  failed=1
}

check "kafka    (list topics)" \
  "${COMPOSE[@]}" exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:19092 --list
check_eventually "kafka    (3 topics provisioned)" \
  bash -c "[ \"\$(\"\$@\" | grep -cxF -e pr.review.requested -e pr.review.retry -e pr.review.dlq)\" = 3 ]" _ \
  "${COMPOSE[@]}" exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:19092 --list
check "postgres (SELECT 1)" \
  "${COMPOSE[@]}" exec -T postgres psql -U reviewbot -d reviewbot -tAc "SELECT 1"
check "schema   (Flyway V1 applied, 3 tables)" \
  bash -c "[ \"\$(\"\$@\")\" = 1,3 ]" _ "${COMPOSE[@]}" exec -T postgres psql -U reviewbot -d reviewbot -tAF, -c \
  "SELECT (SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success), (SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name IN ('reviews', 'installations', 'dlq_entries'))"
check "redis    (PING)" \
  bash -c "[ \"\$(\"\$@\")\" = PONG ]" _ "${COMPOSE[@]}" exec -T redis redis-cli ping
check "wiremock (admin endpoint)" \
  curl -fsS --max-time 5 "$WIREMOCK_URL/__admin/mappings"

if [ "$failed" -ne 0 ]; then
  echo "verify-stack: one or more checks FAILED (is the stack up? docker compose -f infra/docker-compose.yml up -d --wait)"
  exit 1
fi
echo "verify-stack: all checks passed"
