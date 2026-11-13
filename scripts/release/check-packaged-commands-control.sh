#!/usr/bin/env bash
# check-packaged-commands-control.sh — prove that the packaged-command gate can REFUSE.
#
# WHAT IT DOES. It takes the distribution a release publishes, builds two
# BROKEN copies of it, and runs the gate against each. The gate MUST refuse
# both, and each refusal MUST name the row that saw the breakage.
#
# WHY IT EXISTS. A gate that has never refused anything proves nothing: the
# distribution it accepted could be accepted just as well by a gate that
# accepts everything. A row that says "the answer names core.sh" is worth
# nothing until a distribution whose answer does not name core.sh has been
# seen to make that row red.
#
# THE TWO BREAKAGES, and why there are two:
#
#   1. THE LAUNCHER THAT ANSWERS NOTHING. It exits 0 and prints nothing. This
#      is the breakage a gate of exit codes alone would NOT see: every command
#      "succeeded". The rows that read the answer have to be red, and so does
#      the row that asks for a step that does not exist, because a launcher that
#      refuses nothing does not refuse that one either.
#
#   2. THE LAUNCHER THAT ANSWERS ANOTHER IDENTITY. It answers everything the
#      real launcher answers, and reports another version of the product. This
#      is the breakage the identity row exists for, and it is what the second
#      control measures: the gate must be red on that row ALONE, which says
#      that the row saw the identity and not something else.
#
# WHY THE COPY AND NOT THE ARCHIVE. The archive a release publishes is never
# touched: the COPY is what is tampered, and the copy lives in a temporary
# directory that is removed when this control ends. It does not publish
# anything, and it does not sign anything.
#
# Usage:
#   check-packaged-commands-control.sh --candidate-zip <path> [--gate <file>]
#       [--expect-version <v>] [--expect-commit <sha>]
#
# Exit codes: 0 = the gate refused both broken distributions, naming the rows;
# 1 = the control failed (the gate accepted one, or refused it blind);
# 2 = usage error, or the instrument could not run.

set -Eeuo pipefail

CANDIDATE_ZIP=""
GATE=""
EXPECT_VERSION=""
EXPECT_COMMIT=""

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
        --gate)           GATE="${2:-}"; shift 2 ;;
        --expect-version) EXPECT_VERSION="${2:-}"; shift 2 ;;
        --expect-commit)  EXPECT_COMMIT="${2:-}"; shift 2 ;;
        -h|--help)        usage ;;
        *)
            printf 'check-packaged-commands-control: unknown argument %s\n' "$1" >&2
            usage
            ;;
    esac
done

[[ -n "$CANDIDATE_ZIP" ]] || {
    printf 'check-packaged-commands-control: --candidate-zip is required\n' >&2
    exit 2
}
[[ -f "$CANDIDATE_ZIP" ]] || {
    printf 'check-packaged-commands-control: %s does not exist\n' "$CANDIDATE_ZIP" >&2
    exit 2
}

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
[[ -n "$GATE" ]] || GATE="$repo_root/scripts/release/verify-packaged-commands.sh"
[[ -f "$GATE" ]] || {
    printf 'check-packaged-commands-control: there is no gate at %s\n' "$GATE" >&2
    exit 2
}

WORK="$(mktemp -d "${TMPDIR:-/tmp}/pipelinek-packaged-control.XXXXXX")"
cleanup() {
    case "$WORK" in
        /tmp/*|/var/tmp/*|"${TMPDIR:-/tmp}"/*) rm -rf "$WORK" ;;
        *) printf 'check-packaged-commands-control: refusing to delete %s\n' "$WORK" >&2 ;;
    esac
}
trap cleanup EXIT

# The gate's own arguments, so that both controls ask the same question the
# release asks.
#
# The verdict of a run, and whether it was a refusal.
run_gate() {
    # $1 = the directory that holds the distribution; the rest = the archive
    # that directory is packed into.
    local dir="$1" archive="$2"
    # The copy is packed with python and not with zip(1): the packer is a
    # dependency of this control, and python is a dependency of everything else
    # in this repository. The launcher of the copy keeps its mode because the
    # gate makes it executable the way the installer does.
    if ! python3 -I - "$dir" "$archive" <<'PY'
import pathlib, sys, zipfile

root = pathlib.Path(sys.argv[1])
archive = pathlib.Path(sys.argv[2])
with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as bundle:
    for path in sorted(root.rglob("*")):
        if path.is_file():
            bundle.write(path, str(path.relative_to(root)))
PY
    then
        printf 'check-packaged-commands-control: the copy could not be packed\n' >&2
        return 1
    fi
    local gate_args_run=(--candidate-zip "$archive")
    [[ -n "$EXPECT_VERSION" ]] && gate_args_run+=(--expect-version "$EXPECT_VERSION")
    [[ -n "$EXPECT_COMMIT" ]] && gate_args_run+=(--expect-commit "$EXPECT_COMMIT")
    gate_args_run+=(--out "$WORK/gate-verdict.txt")
    if bash "$GATE" "${gate_args_run[@]}" >"$WORK/gate.out" 2>&1; then
        return 0
    fi
    return 1
}

# The row of a verdict, read from the verdict the gate wrote.
row_verdict() {
    # $1 = the row label.
    sed -n "s/^  row $1 .*verdict=\([A-Z]*\).*/\1/p" "$WORK/gate-verdict.txt" | head -1
}

# ----------------------------------------------------------------------------
# 1. The copy of the published distribution.
# ----------------------------------------------------------------------------

CONTROL_DIST="$WORK/dist"
mkdir -p "$CONTROL_DIST"
if ! unzip -q "$CANDIDATE_ZIP" -d "$CONTROL_DIST" >"$WORK/unzip.log" 2>&1; then
    printf 'check-packaged-commands-control: the distribution could not be unpacked\n' >&2
    sed 's/^/    /' "$WORK/unzip.log" >&2
    exit 2
fi

REAL_CLI="$(find "$CONTROL_DIST" -maxdepth 3 -type f -path '*/bin/pipelinek' -print -quit)"
[[ -n "$REAL_CLI" ]] || {
    printf 'check-packaged-commands-control: the distribution carries no bin/pipelinek\n' >&2
    exit 2
}

# ----------------------------------------------------------------------------
# 2. Control one: the launcher that answers nothing and succeeds.
# ----------------------------------------------------------------------------

SILENT_DIST="$WORK/silent"
mkdir -p "$SILENT_DIST"
cp -a "$CONTROL_DIST/." "$SILENT_DIST/"
SILENT_CLI="$(find "$SILENT_DIST" -maxdepth 3 -type f -path '*/bin/pipelinek' -print -quit)"
rm -f "$SILENT_CLI"
cat > "$SILENT_CLI" <<'LAUNCHER'
#!/usr/bin/env bash
# A launcher that answers nothing and always succeeds.
exit 0
LAUNCHER
chmod +x "$SILENT_CLI"

if run_gate "$SILENT_DIST" "$WORK/silent.zip"; then
    printf 'check-packaged-commands-control: the gate ACCEPTED a distribution whose launcher answers nothing\n' >&2
    sed 's/^/    /' "$WORK/gate.out" >&2
    exit 1
fi

for row in A G; do
    verdict="$(row_verdict "$row")"
    if [[ "$verdict" != "FAIL" ]]; then
        printf 'check-packaged-commands-control: the gate refused the silent distribution without making row %s red (it says %s)\n' \
            "$row" "${verdict:-<nothing>}" >&2
        sed 's/^/    /' "$WORK/gate.out" >&2
        exit 1
    fi
done

printf 'negative control: the gate refused a distribution whose launcher answers nothing, naming rows A and G\n'

# ----------------------------------------------------------------------------
# 3. Control two: the launcher that answers another identity.
# ----------------------------------------------------------------------------

OTHER_DIST="$WORK/other"
mkdir -p "$OTHER_DIST"
cp -a "$CONTROL_DIST/." "$OTHER_DIST/"
OTHER_CLI="$(find "$OTHER_DIST" -maxdepth 3 -type f -path '*/bin/pipelinek' -print -quit)"
OTHER_DIR="$(dirname -- "$OTHER_CLI")"
mv "$OTHER_CLI" "$OTHER_DIR/pipelinek.real"
# The packer does not carry the mode of a file, and the real launcher has to
# be runnable for the fake one to delegate to it.
chmod +x "$OTHER_DIR/pipelinek.real"
cat > "$OTHER_CLI" <<'LAUNCHER'
#!/usr/bin/env bash
# A launcher that answers everything the real one answers, and reports another
# version of the product.
if [ "${1:-}" = "version" ]; then
    printf '{"productVersion":"0.0.0-another","buildCommit":"0000000000000000000000000000000000000000"}\n'
    exit 0
fi
exec "$(dirname -- "${BASH_SOURCE[0]}")/pipelinek.real" "$@"
LAUNCHER
chmod +x "$OTHER_CLI"

if run_gate "$OTHER_DIST" "$WORK/other.zip"; then
    printf 'check-packaged-commands-control: the gate ACCEPTED a distribution whose launcher reports another version\n' >&2
    sed 's/^/    /' "$WORK/gate.out" >&2
    exit 1
fi

verdict="$(row_verdict F)"
if [[ "$verdict" != "FAIL" ]]; then
    printf 'check-packaged-commands-control: the gate refused the distribution with another identity without making row F red (it says %s)\n' \
        "${verdict:-<nothing>}" >&2
    sed 's/^/    /' "$WORK/gate.out" >&2
    exit 1
fi

# The rest of the gate still works: the row that went red is the identity, and
# not a gate that had already broken for another reason.
verdict="$(row_verdict A)"
if [[ "$verdict" != "PASS" ]]; then
    printf 'check-packaged-commands-control: the gate refused the distribution with another identity, and row A is %s too\n' \
        "${verdict:-<nothing>}" >&2
    sed 's/^/    /' "$WORK/gate.out" >&2
    exit 1
fi

printf 'negative control: the gate refused a distribution that reports another version, naming row F\n'
