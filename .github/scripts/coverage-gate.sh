#!/usr/bin/env bash
# Overall line-coverage gate (spec section 18: JaCoCo >= 80%).
#
# jacoco:check is per module and cannot gate the whole reactor, so this sums the
# LINE counters of every module's jacoco.csv (written by `./mvnw verify`) and fails
# below the threshold. Modules do not overlap, so the sum is the true aggregate.
# The per-class engine branch rule lives in the parent POM (jacoco-check-engine).
#
# Usage: .github/scripts/coverage-gate.sh [threshold-percent]   (default 80)
set -euo pipefail

threshold="${1:-80}"
mapfile -t reports < <(find . -path '*/target/site/jacoco/jacoco.csv' | sort)

if [ "${#reports[@]}" -eq 0 ]; then
  echo "coverage-gate: no jacoco.csv found - run './mvnw clean verify' first" >&2
  exit 2
fi

table="$(
  for csv in "${reports[@]}"; do
    module="${csv#./}"
    module="${module%%/target/*}"
    awk -F, -v module="$module" '
      NR == 1 { for (i = 1; i <= NF; i++) col[$i] = i; next }
      { missed += $col["LINE_MISSED"]; covered += $col["LINE_COVERED"] }
      END { printf "%s %d %d\n", module, missed, covered }
    ' "$csv"
  done
)"

report="$(
  echo "$table" | awk -v threshold="$threshold" '
    function pct(c, m) { return (c + m) == 0 ? "n/a" : sprintf("%.1f%%", 100 * c / (c + m)) }
    BEGIN { printf "| module | lines covered | lines total | line coverage |\n|---|---|---|---|\n" }
    { printf "| %s | %d | %d | %s |\n", $1, $3, $2 + $3, pct($3, $2); missed += $2; covered += $3 }
    END {
      printf "| **overall** | %d | %d | **%s** (gate: %s%%) |\n", covered, missed + covered, pct(covered, missed), threshold
    }
  '
)"

echo "$report"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  { echo "### JaCoCo line coverage"; echo; echo "$report"; } >> "$GITHUB_STEP_SUMMARY"
fi

echo "$table" | awk -v threshold="$threshold" '
  { missed += $2; covered += $3 }
  END {
    total = missed + covered
    if (total == 0) { print "coverage-gate: no measured lines yet (only *Application mains exist) - gate is vacuous"; exit 0 }
    pct = 100 * covered / total
    if (pct < threshold) { printf "coverage-gate: FAIL - %.1f%% line coverage is below the %s%% gate\n", pct, threshold; exit 1 }
    printf "coverage-gate: ok - %.1f%% >= %s%%\n", pct, threshold
  }
'
