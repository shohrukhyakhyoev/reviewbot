#!/usr/bin/env bash
# Provision the reviewbot Kafka topics (spec §4.1). Idempotent: create-if-absent,
# verify-never-alter.
#
#   absent topic   -> created with the exact §4.1 config            (prints CREATED)
#   existing topic -> only DESCRIBED and compared to the §4.1 config (prints UNCHANGED)
#   drift          -> exit 1, nothing is altered                     (prints DRIFT)
#
# This script NEVER runs `--alter`. Partition count in particular is near-immutable: the
# topics are keyed (hash(key) % N), so changing N reshuffles keys and silently breaks per-PR
# ordering. Changing a partition count means the topic-swap runbook in spec §4.4. Retention /
# cleanup / min.insync drift is also refused rather than "fixed": a human decides.
#
# Keep in sync: topic names and partition counts also exist as constants in `shared` (E1-5).
# THIS script is authoritative for infra; if you change one, change the other.
#
# Usage:
#   provision-topics.sh            create-if-absent + verify (needs a reachable broker)
#   provision-topics.sh --render   print the resolved config and exit (needs no broker)
#
# Environment:
#   KAFKA_ENV          local (default) -> RF=1 / min.insync.replicas=1   (single-broker KRaft)
#                      prod            -> RF=3 / min.insync.replicas=2   (MSK; spec §4.2, §4.4)
#                      `prod` cannot be applied to the local single broker (RF=3 is impossible
#                      there) -- it exists so the prod values are rendered and asserted in CI.
#   BOOTSTRAP_SERVER   default kafka:19092 (compose network)
#   KAFKA_TOPICS_BIN   default /opt/kafka/bin/kafka-topics.sh (apache/kafka image)
#   TOPIC_TABLE        TEST HOOK ONLY: replaces the topic table ("name partitions retention_ms"
#                      per line). Used by verify-topics.sh to prove drift is refused without
#                      touching the real topics. Refused (exit 2) unless
#                      ALLOW_TOPIC_TABLE_OVERRIDE=1 is also set, so a stray TOPIC_TABLE in a
#                      container/Job environment can never silently replace the spec §4.1 set.
#
# Verification is strict: cleanup.policy, retention.ms and min.insync.replicas must be set at
# TOPIC level (spec §4.2: min.insync.replicas is a topic-level setting). A value merely inherited
# from broker defaults reads as unset and is reported as DRIFT.
#
# Exit codes: 0 ok | 1 drift | 2 bad usage / environment | 3 could not describe a topic (broker
# error -- NOT drift) | other non-zero: kafka-topics.sh itself failed (set -e), e.g. on create
set -euo pipefail

KAFKA_ENV="${KAFKA_ENV:-local}"
BOOTSTRAP_SERVER="${BOOTSTRAP_SERVER:-kafka:19092}"
KAFKA_TOPICS_BIN="${KAFKA_TOPICS_BIN:-/opt/kafka/bin/kafka-topics.sh}"

case "$KAFKA_ENV" in
  local) REPLICATION_FACTOR=1 MIN_ISR=1 ;;
  prod) REPLICATION_FACTOR=3 MIN_ISR=2 ;;
  *)
    echo "provision-topics: KAFKA_ENV must be 'local' or 'prod', got '$KAFKA_ENV'" >&2
    exit 2
    ;;
esac

DAY_MS=86400000
# name  partitions  retention.ms   -- spec §4.1. cleanup.policy=delete on all (compaction is wrong
# for event streams). Replication and min.insync.replicas come from KAFKA_ENV above.
DEFAULT_TOPIC_TABLE="pr.review.requested 6 $((7 * DAY_MS))
pr.review.retry 3 $((3 * DAY_MS))
pr.review.dlq 3 $((30 * DAY_MS))"
if [ -n "${TOPIC_TABLE:-}" ] && [ "${ALLOW_TOPIC_TABLE_OVERRIDE:-}" != 1 ]; then
  echo "provision-topics: TOPIC_TABLE is a test hook; refusing to replace the spec §4.1 topic set (set ALLOW_TOPIC_TABLE_OVERRIDE=1 to override)" >&2
  exit 2
fi
TOPIC_TABLE="${TOPIC_TABLE:-$DEFAULT_TOPIC_TABLE}"
CLEANUP_POLICY=delete

# Every row must be exactly "name partitions retention_ms" with numeric counts; a malformed row
# fails here with a clear message instead of as a raw Kafka error halfway through provisioning.
while read -r name partitions retention_ms extra; do
  [ -n "$name" ] || continue
  case "$partitions$retention_ms" in '' | *[!0-9]*) bad=1 ;; *) bad=0 ;; esac
  if [ -z "$partitions" ] || [ -z "$retention_ms" ] || [ -n "$extra" ] || [ "$bad" = 1 ]; then
    echo "provision-topics: malformed topic row '$name $partitions $retention_ms $extra' (want: name partitions retention_ms, numeric)" >&2
    exit 2
  fi
done <<<"$TOPIC_TABLE"

if [ "${1:-}" = "--render" ]; then
  while read -r name partitions retention_ms; do
    [ -n "$name" ] || continue
    echo "$name partitions=$partitions replication=$REPLICATION_FACTOR min.insync.replicas=$MIN_ISR retention.ms=$retention_ms cleanup.policy=$CLEANUP_POLICY"
  done <<<"$TOPIC_TABLE"
  exit 0
fi
if [ $# -gt 0 ]; then
  echo "provision-topics: unknown argument '$1' (only --render is supported)" >&2
  exit 2
fi

topics() { "$KAFKA_TOPICS_BIN" --bootstrap-server "$BOOTSTRAP_SERVER" "$@"; }

# `--list` fails loudly if the broker is unreachable, so "absent" below can never be confused
# with "could not ask".
existing_topics="$(topics --list)"

# Value of `key` in a comma-separated "k=v,k=v" topic-config string (empty if unset).
config_value() {
  local key="$1" configs="$2"
  tr ',' '\n' <<<"$configs" | sed -n "s/^${key//./\\.}=//p"
}

drift=0

verify_topic() {
  local name="$1" want_partitions="$2" want_retention="$3"
  local desc first actual_partitions actual_rf configs problems=""

  # Called under `if`, where set -e is off: check the describe explicitly, so a broker error is
  # never misreported as drift (empty output would otherwise read as "partitions: <unset>").
  if ! desc="$(topics --describe --topic "$name")" || [ -z "$desc" ]; then
    echo "ERROR     $name -- could not describe topic (broker error, not drift)" >&2
    exit 3
  fi
  first="$(sed -n 1p <<<"$desc")"
  actual_partitions="$(sed -n 's/.*PartitionCount: *\([0-9][0-9]*\).*/\1/p' <<<"$first")"
  actual_rf="$(sed -n 's/.*ReplicationFactor: *\([0-9][0-9]*\).*/\1/p' <<<"$first")"
  configs="$(sed -n 's/.*Configs: *//p' <<<"$first")"

  check() { # label expected actual
    if [ "$2" != "$3" ]; then problems="$problems
    $1: expected $2, found ${3:-<unset>}"; fi
  }
  check "partitions" "$want_partitions" "$actual_partitions"
  check "replication-factor" "$REPLICATION_FACTOR" "$actual_rf"
  check "retention.ms" "$want_retention" "$(config_value retention.ms "$configs")"
  check "cleanup.policy" "$CLEANUP_POLICY" "$(config_value cleanup.policy "$configs")"
  check "min.insync.replicas" "$MIN_ISR" "$(config_value min.insync.replicas "$configs")"

  if [ -n "$problems" ]; then
    echo "DRIFT     $name -- existing topic does not match spec §4.1:$problems" >&2
    if [ "$actual_partitions" != "$want_partitions" ]; then
      echo "          Partition count is NOT altered (keyed topic: hash(key) % N would reshuffle and break per-PR ordering). See spec §4.4 topic-swap runbook." >&2
    fi
    drift=1
    return 1
  fi
}

while read -r name partitions retention_ms; do
  [ -n "$name" ] || continue
  if grep -Fxq -- "$name" <<<"$existing_topics"; then
    if verify_topic "$name" "$partitions" "$retention_ms"; then
      echo "UNCHANGED $name"
    fi
  else
    topics --create --if-not-exists --topic "$name" \
      --partitions "$partitions" --replication-factor "$REPLICATION_FACTOR" \
      --config "cleanup.policy=$CLEANUP_POLICY" \
      --config "retention.ms=$retention_ms" \
      --config "min.insync.replicas=$MIN_ISR" >/dev/null
    # Verify what was just created too: an `--if-not-exists` race with another provisioner
    # must not let a mismatched topic slip through.
    if verify_topic "$name" "$partitions" "$retention_ms"; then
      echo "CREATED   $name (partitions=$partitions replication=$REPLICATION_FACTOR min.insync.replicas=$MIN_ISR retention.ms=$retention_ms)"
    fi
  fi
done <<<"$TOPIC_TABLE"

if [ "$drift" -ne 0 ]; then
  echo "provision-topics: FAILED -- existing topic(s) differ from spec; nothing was altered." >&2
  exit 1
fi
echo "provision-topics: OK (env=$KAFKA_ENV)"
