#!/usr/bin/env bash
# Run one or all example pipelines with the real V2 CLI binary and assert
# expected exit codes, terminal outcomes and (for 07-10) event contracts.
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
SCRATCH="${TMPDIR:-/tmp}/pipeline-examples"
mkdir -p "$SCRATCH"

if [[ ! -x "$BIN" ]]; then
  echo "Application binary not found. Building it first (./gradlew -p v2 :pipeline-application:installDist)..." >&2
  (cd "$ROOT" && ./gradlew -p v2 :pipeline-application:installDist) >&2
fi

# Expected terminal outcome of the LAST RunFinished event per example.
declare -A EXPECTED_OUTCOME=(
  ["05-failing-step.pipeline.kts"]="failure"   # typed failure demo: failing is the point
  ["07-catch-error.pipeline.kts"]="unstable"   # outer catchError suppresses as UNSTABLE
  ["10-timeout.pipeline.kts"]="failure"        # timeout deadline aborts the over-running sh
)
# Expected CLI exit code (defaults to 0).
declare -A EXPECTED_EXIT=(
  ["05-failing-step.pipeline.kts"]=1
  ["10-timeout.pipeline.kts"]=1
)

fail() { echo "   ✗ $1" >&2; exit 1; }

# events > max(occurredAt of the first file's events): CLI reprints prior
# journal entries with their ORIGINAL timestamps on durable reruns.
new_events() {
  local t1
  t1="$(python3 -c "import json,sys;print(max(e['occurredAt'] for e in json.load(open(sys.argv[1]))))" "$1")"
  python3 -c "import json,sys;print(json.dumps([e for e in json.load(open(sys.argv[2])) if e['occurredAt']>'$t1']))" "$1" "$2"
}

count_kind() { echo "$1" | python3 -c "import json,sys;print(sum(e['kind']==sys.argv[1] for e in json.load(sys.stdin)))" "$2"; }

check_07() {
  local f="$1"
  local new
  new="$(cat "$f")"
  local nfail nunst
  nfail="$(echo "$new" | python3 -c "import json,sys;ev=json.load(sys.stdin);print(sum(e['kind']=='CatchErrorTriggered' and e.get('buildResult')=='FAILURE' for e in ev))")"
  nunst="$(echo "$new" | python3 -c "import json,sys;ev=json.load(sys.stdin);print(sum(e['kind']=='CatchErrorTriggered' and e.get('buildResult')=='UNSTABLE' for e in ev))")"
  [[ "$nfail" == "1" ]] || fail "07 contract: expected exactly 1 CatchErrorTriggered(FAILURE), got '$nfail'"
  [[ "$nunst" == "1" ]] || fail "07 contract: expected exactly 1 CatchErrorTriggered(UNSTABLE), got $nunst"
  # innermost-first ordering (ERR-S-007)
  echo "$new" | python3 -c "import json,sys;ev=[e for e in json.load(sys.stdin) if e['kind']=='CatchErrorTriggered'];sys.exit(0 if len(ev)==2 and ev[0]['buildResult']=='FAILURE' and ev[1]['buildResult']=='UNSTABLE' else 1)" \
    || fail "07 contract: inner FAILURE must precede outer UNSTABLE"
  echo "$new" | python3 -c "import json,sys;sys.exit(0 if any(e['kind']=='EchoOutputCaptured' and 'continues after nested catch' in e.get('content','') for e in json.load(sys.stdin)) else 1)" \
    || fail "07 contract: echo after nested catchError must still execute"
  echo "   ✓ 07 contract: 2 CatchErrorTriggered (FAILURE→UNSTABLE, innermost-first) + post-catch echo"
}

check_08() {
  local f1="$1" f2="$2"
  local new nb ns
  new="$(new_events "$f1" "$f2")"
  nb="$(count_kind "$new" ParallelBranchStarted)"
  ns="$(count_kind "$new" StepStarted)"
  [[ "$nb" == "0" ]] || fail "08 contract: durable rerun fabricated $nb ParallelBranchStarted"
  [[ "$ns" == "0" ]] || fail "08 contract: durable rerun re-executed $ns steps"
  echo "   ✓ 08 contract: second run reuses terminal aggregate (0 branch events, 0 step events)"
}

check_09() {
  local f="$1"
  python3 - "$f" <<'EOF' || fail "09 contract: expected RetryAttempt fail then success"
import json, sys
ev = [e for e in json.load(open(sys.argv[1])) if e['kind'] == 'RetryAttemptFinished']
ok = (len(ev) == 2 and ev[0].get('outcome') == 'failed' and ev[1].get('outcome') == 'succeeded')
sys.exit(0 if ok else 1)
EOF
  echo "   ✓ 09 contract: RetryAttemptFinished failed→succeeded (exactly 2 attempts)"
}

check_10() {
  local f="$1"
  python3 - "$f" <<'EOF' || fail "10 contract: expected TimeoutScheduled + timed-out sh"
import json, sys
ev = json.load(open(sys.argv[1]))
ok = (sum(e['kind'] == 'TimeoutScheduled' for e in ev) >= 1
      and any(e['kind'] == 'StepFailed' and 'timed out' in e.get('message', '') for e in ev))
sys.exit(0 if ok else 1)
EOF
  echo "   ✓ 10 contract: TimeoutScheduled + sh aborted by deadline"
}

run_one() {
  local script="$1"; shift || true
  local name outcome expected rc f1 f2 db
  name="$(basename "$script")"
  expected="${EXPECTED_OUTCOME[$name]:-success}"

  echo "── $name"

  # Durable examples run TWICE with the same --db; the second run proves reuse.
  f1="$SCRATCH/$name.run1.json"
  f2="$SCRATCH/$name.run2.json"
  case "$name" in
    06-*|08-*|09-*)
      db="$SCRATCH/$name.db"
      rm -f "$db" /tmp/pipeline-retry-done
      set +e
      "$BIN" run --db "$db" "$ROOT/examples/$script" "$@" > "$f1"
      rc=$?
      set -e
      [[ $rc -eq 0 ]] || fail "$name first run exited $rc (expected 0)"
      ;;
    *)
      set +e
      "$BIN" run "$ROOT/examples/$script" "$@" > "$f1"
      rc=$?
      set -e
      ;;
  esac

  if [[ $rc -ne "${EXPECTED_EXIT[$name]:-0}" ]]; then
    fail "$name exit=$rc but expected ${EXPECTED_EXIT[$name]:-0}"
  fi

  outcome="$(python3 -c "import json;print(json.load(open('$f1'))[-1].get('outcome'))" "$f1")"
  [[ "$outcome" == "$expected" ]] || fail "$name outcome=$outcome but expected $expected"

  case "$name" in
    06-*|08-*|09-*)
      [[ -n "${db:-}" ]] || fail "$name: internal error, db not set"
      rm -f /tmp/pipeline-retry-done
      set +e
      "$BIN" run --db "$db" "$ROOT/examples/$script" "$@" > "$f2"
      rc=$?
      set -e
      [[ $rc -eq 0 ]] || fail "$name second run exited $rc (expected 0)"
      ;;
  esac

  case "$name" in
    07-*) check_07 "$f1" ;;
    08-*) check_08 "$f1" "$f2" ;;
    09-*) check_09 "$f2" ;;
    10-*) check_10 "$f1" ;;
  esac

  echo "   ✓ $name exit=$rc outcome=$outcome (expected $expected)"
}

if [[ $# -eq 0 ]]; then
  status=0
  for f in "$ROOT"/examples/*.pipeline.kts; do
    run_one "$(basename "$f")" || status=1
  done
  exit "$status"
else
  run_one "$@"
fi
