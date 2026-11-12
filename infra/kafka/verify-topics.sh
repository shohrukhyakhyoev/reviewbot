#!/usr/bin/env bash
# End-to-end check of topic provisioning (ticket E2-4). Used by the CI job `kafka-topics`;
# also runnable locally against the compose stack (Kafka must be up):
#
#   docker compose -f infra/docker-compose.yml up -d --wait kafka
#   bash infra/kafka/verify-topics.sh
#
# What it proves:
#   1. prod values render as RF=3 / min.insync.replicas=2 (no broker needed to assert this)
#   2. run #1 leaves the three topics with the exact spec §4.1 config (read back via --describe)
#   3. run #2 is a verified no-op: no CREATED lines, and `--describe` output is byte-identical
#      (same TopicIds, so nothing was deleted/recreated)
#   4. an existing topic with the wrong partition count (or drifted config) makes the script
#      fail loudly and the topic is left as it was -- never "fixed"
#
# The expectations below are written out independently of provision-topics.sh on purpose:
# a test that reads its expected values from the code under test proves nothing.
#
# EXPECT_FRESH=1 additionally requires run #1 to CREATE all three topics (CI starts empty).
# Never run this against a Kafka whose real topics you care about holding drifted state in:
# it only creates/deletes its own scratch topic `e2-4.scratch`.
set -u

# Git Bash (Windows) rewrites container paths like /opt/kafka/... into host paths.
export MSYS_NO_PATHCONV=1

cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1 # -> infra/ (where docker-compose.yml lives)
PROVISION=./kafka/provision-topics.sh
KAFKA_TOPICS=/opt/kafka/bin/kafka-topics.sh
SCRATCH=e2-4.scratch

failed=0
pass() { echo "PASS  $1"; }
fail() {
  echo "FAIL  $1"
  failed=1
}
assert_eq() { # label expected actual
  if [ "$2" = "$3" ]; then pass "$1"; else fail "$1 (expected '$2', got '$3')"; fi
}

kt() { docker compose exec -T kafka "$KAFKA_TOPICS" --bootstrap-server localhost:19092 "$@"; }
provision() { docker compose run --rm -T kafka-init; } # same entrypoint/env as `compose up`
describe_all() {
  for t in pr.review.requested pr.review.retry pr.review.dlq; do kt --describe --topic "$t" || return 1; done
}
cleanup() { kt --delete --topic "$SCRATCH" >/dev/null 2>&1; }
trap cleanup EXIT

# --- 1. prod values render (and are asserted) without a broker ---------------------------------
expected_prod="pr.review.requested partitions=6 replication=3 min.insync.replicas=2 retention.ms=604800000 cleanup.policy=delete
pr.review.retry partitions=3 replication=3 min.insync.replicas=2 retention.ms=259200000 cleanup.policy=delete
pr.review.dlq partitions=3 replication=3 min.insync.replicas=2 retention.ms=2592000000 cleanup.policy=delete"
assert_eq "prod render: RF=3 / ISR=2, spec §4.1 counts + retention" \
  "$expected_prod" "$(KAFKA_ENV=prod bash "$PROVISION" --render)"

# --- 2. run #1 ---------------------------------------------------------------------------------
out1="$(provision 2>&1)"
rc=$?
echo "$out1" | sed 's/^/      run1: /'
assert_eq "run 1 exits 0" 0 "$rc"
if [ "${EXPECT_FRESH:-0}" = 1 ]; then
  assert_eq "run 1 on a fresh broker CREATED all three topics" 3 "$(grep -c '^CREATED ' <<<"$out1")"
fi

# --- 3. exact §4.1 config, read back from the broker (local env: RF=1 / ISR=1) ------------------
check_topic() { # name partitions retention_ms
  local line configs
  line="$(kt --describe --topic "$1" | sed -n 1p)"
  configs="$(sed -n 's/.*Configs: *//p' <<<"$line")"
  assert_eq "$1 partitions" "$2" "$(sed -n 's/.*PartitionCount: *\([0-9]*\).*/\1/p' <<<"$line")"
  assert_eq "$1 replication-factor (local)" 1 "$(sed -n 's/.*ReplicationFactor: *\([0-9]*\).*/\1/p' <<<"$line")"
  assert_eq "$1 retention.ms" "$3" "$(tr ',' '\n' <<<"$configs" | sed -n 's/^retention\.ms=//p')"
  assert_eq "$1 cleanup.policy" delete "$(tr ',' '\n' <<<"$configs" | sed -n 's/^cleanup\.policy=//p')"
  assert_eq "$1 min.insync.replicas (local)" 1 "$(tr ',' '\n' <<<"$configs" | sed -n 's/^min\.insync\.replicas=//p')"
}
check_topic pr.review.requested 6 604800000
check_topic pr.review.retry 3 259200000
check_topic pr.review.dlq 3 2592000000

# --- 4. run #2 is a no-op ----------------------------------------------------------------------
snapshot1="$(describe_all)"
assert_eq "snapshot is not vacuous (3 topics described)" 3 "$(grep -c '^Topic:' <<<"$snapshot1")"
out2="$(provision 2>&1)"
rc=$?
echo "$out2" | sed 's/^/      run2: /'
assert_eq "run 2 exits 0" 0 "$rc"
assert_eq "run 2 created nothing" 0 "$(grep -c '^CREATED ' <<<"$out2")"
assert_eq "run 2 reported all three UNCHANGED" 3 "$(grep -c '^UNCHANGED ' <<<"$out2")"
snapshot2="$(describe_all)"
assert_eq "second snapshot is not vacuous (3 topics described)" 3 "$(grep -c '^Topic:' <<<"$snapshot2")"
assert_eq "topic state identical after run 2 (TopicIds, partitions, configs)" "$snapshot1" "$snapshot2"

# --- 5. drift is refused, never fixed ------------------------------------------------------------
# Scratch topic with 2 partitions and otherwise spec-correct config (RF=1/ISR=1/7d/delete).
cleanup
kt --create --topic "$SCRATCH" --partitions 2 --replication-factor 1 \
  --config cleanup.policy=delete --config retention.ms=604800000 --config min.insync.replicas=1 >/dev/null ||
  fail "could not create scratch topic"

out3="$(docker compose run --rm -T -e ALLOW_TOPIC_TABLE_OVERRIDE=1 -e "TOPIC_TABLE=$SCRATCH 3 604800000" kafka-init 2>&1)"
rc=$?
echo "$out3" | sed 's/^/      drift: /'
if [ "$rc" -ne 0 ]; then pass "wrong partition count -> script exits non-zero (rc=$rc)"; else fail "wrong partition count was accepted (rc=0)"; fi
assert_eq "wrong partition count is reported as DRIFT" 1 "$(grep -c '^DRIFT  *'"$SCRATCH" <<<"$out3")"
assert_eq "drift report names the partition mismatch" 1 "$(grep -c 'partitions: expected 3, found 2' <<<"$out3")"
assert_eq "scratch topic still has 2 partitions (not altered)" 2 \
  "$(kt --describe --topic "$SCRATCH" | sed -n 1p | sed -n 's/.*PartitionCount: *\([0-9]*\).*/\1/p')"

out4="$(docker compose run --rm -T -e ALLOW_TOPIC_TABLE_OVERRIDE=1 -e "TOPIC_TABLE=$SCRATCH 2 259200000" kafka-init 2>&1)"
rc=$?
if [ "$rc" -ne 0 ]; then pass "drifted retention -> script exits non-zero (rc=$rc)"; else fail "drifted retention was accepted (rc=0)"; fi
assert_eq "drift report names the retention mismatch (not some other failure)" 1 \
  "$(grep -c 'retention.ms: expected 259200000, found 604800000' <<<"$out4")"

# --- 6. guard rails of the script itself -----------------------------------------------------
# A stray TOPIC_TABLE must not silently replace the spec topic set.
out5="$(docker compose run --rm -T -e "TOPIC_TABLE=$SCRATCH 3 604800000" kafka-init 2>&1)"
assert_eq "TOPIC_TABLE without ALLOW_TOPIC_TABLE_OVERRIDE is refused (rc=2)" 2 "$?"
out6="$(docker compose run --rm -T -e ALLOW_TOPIC_TABLE_OVERRIDE=1 -e "TOPIC_TABLE=$SCRATCH six 604800000" kafka-init 2>&1)"
assert_eq "malformed topic row is refused with a usage error (rc=2)" 2 "$?"
assert_eq "scratch topic retention.ms untouched" 604800000 \
  "$(kt --describe --topic "$SCRATCH" | sed -n 1p | tr ',' '\n' | sed -n 's/^.*retention\.ms=//p')"

if [ "$failed" -ne 0 ]; then
  echo "verify-topics: FAILED"
  exit 1
fi
echo "verify-topics: all checks passed"
