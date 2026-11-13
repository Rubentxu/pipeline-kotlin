#!/usr/bin/env bash
# verify-packaged-commands.sh — the acceptance gate of the PACKAGED commands.
#
# THE QUESTION
#   "Does the distribution this release publishes answer the commands a
#    consumer asks it, with the exit codes and the results the product
#    promises?"
#
# WHY IT IS A RELEASE GATE AND NOT A TEST
#   - it consumes the PUBLISHED archive: the bytes that leave this tree, not a
#     distribution assembled from the tree's own build output;
#   - a unit test may not shell out, and this starts a JVM once per command.
#   So it is a RELEASE gate: scripts/release-pipelinek.sh runs it over the
#   STAGED archive, before the tag is cut, and a red row stops the publication.
#
# WHAT IT MEASURES
#   Six commands a consumer asks the installed command, and the TWO facts each
#   of them has to carry: the exit code, and the result. A command that exits 0
#   and prints nothing has not answered; a command that prints an answer and
#   exits 2 has not answered either.
#
#   A  steps                the catalogue of the steps and the directives
#   B  steps --json         the same catalogue, machine readable
#   C  describe sh          the card of one step
#   D  migrate --jenkins sh what this build offers for the steps of Jenkins
#   E  --help               the verbs the command has
#   F  version --json       the identity of the bytes
#   G  describe <unknown>   the refusal of a step that does not exist
#
#   Row G is what makes the exit codes mean something: a launcher that answered
#   0 to everything would pass rows A to F, and row G says that the exit code
#   of a refusal is not a success.
#
#   Row F is where the identity of the bytes is checked: the archive has to
#   report the version and the commit the release publishes. An archive whose
#   launcher answers with another release's identity is not this release, and
#   the release page would say otherwise.
#
# WHAT IT DOES NOT DO
#   It does not rebuild anything, it does not publish anything, and it does not
#   modify any tracked file. It unpacks the archive, starts the packaged
#   launcher, reads what it answers, and throws the unpacked tree away.
#
# Usage:
#   verify-packaged-commands.sh --candidate-zip <path> [--expect-version <v>]
#       [--expect-commit <sha>] [--out <file>]
#
# EXIT STATUS
#   0  every row passed
#   1  a row failed
#   2  the instrument could not run: no JVM on this machine, no launcher in the
#      archive, an archive that could not be unpacked. A check that could not
#      run is NOT a pass, and it is not a failure of the artifact either: it
#      is a broken instrument, and it says so.

set -Eeuo pipefail

CANDIDATE_ZIP=""
EXPECT_VERSION=""
EXPECT_COMMIT=""
OUT_FILE=""

# How long ONE command may take. The launcher starts a JVM, and a JVM that
# cannot start does not come back: without a bound, a row that hangs is a
# publication that hangs with it.
COMMAND_TIMEOUT="${PIPELINEK_PACKAGED_COMMAND_TIMEOUT:-180}"

usage() {
    # The header is printed as it is written, up to the first line that is
    # not a comment. A hardcoded line range goes stale the moment the text
    # grows, and a stale range truncates the instructions in silence.
    awk 'NR > 1 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
    exit 2
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --candidate-zip)  CANDIDATE_ZIP="${2:-}"; shift 2 ;;
        --expect-version) EXPECT_VERSION="${2:-}"; shift 2 ;;
        --expect-commit)  EXPECT_COMMIT="${2:-}"; shift 2 ;;
        --out)            OUT_FILE="${2:-}"; shift 2 ;;
        -h|--help)        usage ;;
        *)
            printf 'verify-packaged-commands: unknown argument %s\n' "$1" >&2
            usage
            ;;
    esac
done

if [[ -z "$CANDIDATE_ZIP" ]]; then
    printf 'verify-packaged-commands: --candidate-zip is required\n' >&2
    exit 2
fi

if [[ ! -f "$CANDIDATE_ZIP" ]]; then
    printf 'verify-packaged-commands: %s does not exist\n' "$CANDIDATE_ZIP" >&2
    exit 2
fi

# The work area. Everything bulky lives here and is deleted when the run ends,
# successful or not; only the compact verdict survives, and only if the caller
# asked for it.
WORK="$(mktemp -d "${TMPDIR:-/tmp}/pipelinek-packaged.XXXXXX")"
cleanup() {
    case "$WORK" in
        /tmp/*|/var/tmp/*|"${TMPDIR:-/tmp}"/*) rm -rf "$WORK" ;;
        *) printf 'verify-packaged-commands: refusing to delete %s\n' "$WORK" >&2 ;;
    esac
}
trap cleanup EXIT

STARTED_AT="$(date +%s)"

log() { printf 'packaged-commands: %s\n' "$*"; }

# ----------------------------------------------------------------------------
# 1. The bytes under test, and the launcher inside them.
# ----------------------------------------------------------------------------

DIST_ROOT="$WORK/dist"
mkdir -p "$DIST_ROOT"

if ! unzip -q "$CANDIDATE_ZIP" -d "$DIST_ROOT" >"$WORK/unzip.log" 2>&1; then
    printf 'verify-packaged-commands: the archive could not be unpacked\n' >&2
    sed 's/^/    /' "$WORK/unzip.log" >&2
    exit 2
fi

CLI="$(find "$DIST_ROOT" -maxdepth 3 -type f -path '*/bin/pipelinek' -print -quit)"
if [[ -z "$CLI" ]]; then
    printf 'verify-packaged-commands: the archive carries no bin/pipelinek\n' >&2
    exit 2
fi

# The launcher is made executable the way the installer makes it executable:
# a consumer installs the archive and runs the command, and the installer
# guarantees that the command runs. What this gate measures is the product's
# answer, not the permission bits the archive happens to carry.
chmod +x "$CLI"

# The launcher needs a JVM. A machine without one cannot answer, and that is
# a fact about the machine: it is reported as a broken instrument, and the
# rows are not run at all.
if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
    :
elif command -v java >/dev/null 2>&1; then
    :
else
    printf 'verify-packaged-commands: no JVM on this machine; the check could not run\n' >&2
    exit 2
fi

# The cache of the run lives here and not under the home of the operator: the
# product writes its compiled scripts under XDG_CACHE_HOME, and what this gate
# measures is the answer of the packaged command, not what it left behind.
#
# The HOME of the operator is NOT changed: the JVM on this machine is often
# reached through a version manager, and a manager that finds no home finds no
# JVM. The six commands this gate asks write nothing else: they do not compile
# a script, so they have no state of their own to isolate.
export XDG_CACHE_HOME="$WORK/cache"
mkdir -p "$XDG_CACHE_HOME"

# ----------------------------------------------------------------------------
# 2. The rows.
# ----------------------------------------------------------------------------

declare -a ROW_NAMES=()
declare -a ROW_COMMANDS=()
declare -a ROW_VERDICTS=()
declare -a ROW_DETAILS=()

record() {
    ROW_NAMES+=("$1")
    ROW_COMMANDS+=("$2")
    ROW_VERDICTS+=("$3")
    ROW_DETAILS+=("$4")
}

# run_packaged <row label> <arguments...>
# Runs the PACKAGED launcher and prints its exit code. The answer goes to the
# row's own files, so that the reason of a red row is in the verdict and not
# in a log the run deletes.
run_packaged() {
    local label="$1"; shift
    local code=0
    timeout "$COMMAND_TIMEOUT" "$CLI" "$@" \
        >"$WORK/row-$label.stdout" 2>"$WORK/row-$label.stderr" || code=$?
    printf '%s' "$code"
}

# The reason of a row, flattened into one line so that it can travel inside
# the row's own line of the report.
stderr_reason() {
    local label="$1"
    grep -v '^[[:space:]]*$' "$WORK/row-$label.stderr" 2>/dev/null \
        | tail -2 | tr '\n' ' ' | sed 's/[[:space:]]*$//' || true
}

# --- row A: steps ------------------------------------------------------------

code="$(run_packaged A steps)"
if [[ "$code" != "0" ]]; then
    record A "steps" FAIL "exit=$code; the catalogue did not answer. $(stderr_reason A)"
elif ! grep -Eq '^step +core\.sh([[:space:]]|$)' "$WORK/row-A.stdout"; then
    record A "steps" FAIL "the listing does not name the step core.sh"
else
    record A "steps" PASS "exit=0; the listing names core.sh among $(grep -c '' "$WORK/row-A.stdout") rows"
fi

# --- row B: steps --json -----------------------------------------------------

code="$(run_packaged B steps --json)"
if [[ "$code" != "0" ]]; then
    record B "steps --json" FAIL "exit=$code; the catalogue did not answer. $(stderr_reason B)"
else
    detail="$(python3 -I - "$WORK/row-B.stdout" <<'PY'
import json, sys

try:
    with open(sys.argv[1], encoding="utf-8") as handle:
        doc = json.load(handle)
except (OSError, ValueError) as error:
    print(f"the answer is not JSON: {error}")
    raise SystemExit(1)

schema = str(doc.get("schema", ""))
if not schema.startswith("pipelinek.steps/"):
    print(f"the schema is not pipelinek.steps/<n>: {schema!r}")
    raise SystemExit(1)

entries = doc.get("entries") or []
if not entries:
    print("the catalogue carries no entries")
    raise SystemExit(1)

keys = [str(entry.get("key", "")) for entry in entries]
if "core.sh" not in keys:
    print("the catalogue does not carry core.sh")
    raise SystemExit(1)

print(f"schema={schema}; {len(entries)} entries; core.sh is one of them")
PY
)" && record B "steps --json" PASS "exit=0; $detail" \
  || record B "steps --json" FAIL "$detail"
fi

# --- row C: describe sh ------------------------------------------------------

code="$(run_packaged C describe sh)"
if [[ "$code" != "0" ]]; then
    record C "describe sh" FAIL "exit=$code; the card did not answer. $(stderr_reason C)"
elif ! grep -Eq '^core\.sh$' "$WORK/row-C.stdout"; then
    record C "describe sh" FAIL "the answer does not name core.sh"
elif ! grep -Eq '^  kind +step$' "$WORK/row-C.stdout"; then
    record C "describe sh" FAIL "the card does not say that core.sh is a step"
else
    record C "describe sh" PASS "exit=0; the card names core.sh and says it is a step"
fi

# --- row D: migrate --jenkins sh ---------------------------------------------

code="$(run_packaged D migrate --jenkins sh)"
if [[ "$code" != "0" ]]; then
    record D "migrate --jenkins sh" FAIL "exit=$code; the offer did not answer. $(stderr_reason D)"
elif ! grep -Eq '^  status +the catalogue declares an equivalent$' "$WORK/row-D.stdout"; then
    record D "migrate --jenkins sh" FAIL "the answer does not declare an equivalent for sh"
elif ! grep -Eq '^  equivalent +core\.sh([[:space:]]|$)' "$WORK/row-D.stdout"; then
    record D "migrate --jenkins sh" FAIL "the answer names no equivalent of core.sh for sh"
else
    record D "migrate --jenkins sh" PASS "exit=0; the offer declares core.sh the equivalent of sh"
fi

# --- row E: --help -----------------------------------------------------------

code="$(run_packaged E --help)"
if [[ "$code" != "0" ]]; then
    record E "--help" FAIL "exit=$code; the usage did not answer. $(stderr_reason E)"
elif ! grep -Eq '^Usage: pipelinek ' "$WORK/row-E.stdout"; then
    record E "--help" FAIL "the answer does not state how the command is used"
else
    missing=""
    for verb in steps describe migrate version; do
        if ! grep -Eq "^  ${verb} +" "$WORK/row-E.stdout"; then
            missing="${missing}${missing:+, }${verb}"
        fi
    done
    if [[ -n "$missing" ]]; then
        record E "--help" FAIL "the usage does not name these verbs: ${missing}"
    else
        record E "--help" PASS "exit=0; the usage names the verbs steps, describe, migrate and version"
    fi
fi

# --- row F: version --json ---------------------------------------------------

code="$(run_packaged F version --json)"
if [[ "$code" != "0" ]]; then
    record F "version --json" FAIL "exit=$code; the identity did not answer. $(stderr_reason F)"
else
    detail="$(python3 -I - "$WORK/row-F.stdout" "$EXPECT_VERSION" "$EXPECT_COMMIT" <<'PY'
import json, sys

expected_version, expected_commit = sys.argv[2], sys.argv[3]

try:
    with open(sys.argv[1], encoding="utf-8") as handle:
        doc = json.load(handle)
except (OSError, ValueError) as error:
    print(f"the answer is not JSON: {error}")
    raise SystemExit(1)

version = str(doc.get("productVersion", ""))
commit = str(doc.get("buildCommit", ""))

if not version:
    print("the answer carries no productVersion")
    raise SystemExit(1)
if not commit:
    print("the answer carries no buildCommit")
    raise SystemExit(1)
if expected_version and version != expected_version:
    print(f"the archive reports the version {version} and the release publishes {expected_version}")
    raise SystemExit(1)
if expected_commit and commit != expected_commit:
    print(f"the archive reports the commit {commit} and the release publishes {expected_commit}")
    raise SystemExit(1)

print(f"productVersion={version}; buildCommit={commit}")
PY
)" && record F "version --json" PASS "exit=0; $detail" \
  || record F "version --json" FAIL "$detail"
fi

# --- row G: the refusal of a step that does not exist ------------------------
#
# The row that makes the exit codes mean something. Without it, a launcher that
# answered 0 to every question would pass every other row, and the column of
# exit codes would be decoration.

code="$(run_packaged G describe __no-such-step__)"
if [[ "$code" == "0" ]]; then
    record G "describe __no-such-step__" FAIL \
        "exit=0; the refusal of a step that does not exist is not a success"
else
    record G "describe __no-such-step__" PASS \
        "exit=$code; the refusal of a step that does not exist is not a success"
fi

# ----------------------------------------------------------------------------
# 3. The verdict. One line, and it says what ran and what did not.
# ----------------------------------------------------------------------------

ELAPSED=$(( $(date +%s) - STARTED_AT ))
FAILED=0
SUMMARY_ROWS=""
for index in "${!ROW_NAMES[@]}"; do
    SUMMARY_ROWS+="${ROW_NAMES[$index]}:${ROW_VERDICTS[$index]} "
    if [[ "${ROW_VERDICTS[$index]}" == "FAIL" ]]; then
        FAILED=1
    fi
done
SUMMARY_ROWS="${SUMMARY_ROWS% }"

if [[ "$FAILED" == "0" ]]; then
    VERDICT="PASS"
else
    VERDICT="FAIL"
fi

{
    printf 'packaged-commands: %s rows=%s zip=%s elapsed=%ss\n' \
        "$VERDICT" "$SUMMARY_ROWS" "$(basename "$CANDIDATE_ZIP")" "$ELAPSED"
    for index in "${!ROW_NAMES[@]}"; do
        printf '  row %s command=%s verdict=%s %s\n' \
            "${ROW_NAMES[$index]}" "${ROW_COMMANDS[$index]}" \
            "${ROW_VERDICTS[$index]}" "${ROW_DETAILS[$index]}"
    done
} | tee "$WORK/verdict.txt"

if [[ -n "$OUT_FILE" ]]; then
    mkdir -p "$(dirname -- "$OUT_FILE")"
    cp "$WORK/verdict.txt" "$OUT_FILE"
fi

if [[ "$FAILED" != "0" ]]; then
    printf 'verify-packaged-commands: the packaged commands FAILED\n' >&2
    exit 1
fi

log "the packaged commands PASSED (rows: $SUMMARY_ROWS)"
