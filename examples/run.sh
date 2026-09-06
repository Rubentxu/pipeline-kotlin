#!/usr/bin/env bash
# Run one or all example pipelines with the real V2 CLI binary.
#
# Usage:
#   examples/run.sh                          # run all examples
#   examples/run.sh 01-hello.pipeline.kts    # run a single example
#   examples/run.sh 06-durable.pipeline.kts --db /tmp/my-journal.db
#
# The binary is produced by: ./gradlew -p v2 :pipeline-application:installDist
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="$ROOT/v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application"

if [[ ! -x "$BIN" ]]; then
  echo "Application binary not found. Building it first (./gradlew -p v2 :pipeline-application:installDist)..." >&2
  (cd "$ROOT" && ./gradlew -p v2 :pipeline-application:installDist) >&2
fi

declare -A EXPECTED_EXIT=(
  ["05-failing-step.pipeline.kts"]=1   # typed failure demo: non-zero exit is the point
)

run_one() {
  local script="$1"; shift || true
  local name
  name="$(basename "$script")"
  local expected="${EXPECTED_EXIT[$name]:-0}"

  echo "── $name"
  set +e
  "$BIN" run "$ROOT/examples/$script" "$@"
  local rc=$?
  set -e

  if [[ "$rc" -eq "$expected" ]]; then
    echo "   ✓ $name exit=$rc (expected $expected)"
  else
    echo "   ✗ $name exit=$rc but expected $expected" >&2
    return 1
  fi
}

if [[ $# -eq 0 ]]; then
  status=0
  for f in "$ROOT"/examples/0*.pipeline.kts; do
    run_one "$(basename "$f")" || status=1
  done
  exit "$status"
else
  run_one "$@"
fi
