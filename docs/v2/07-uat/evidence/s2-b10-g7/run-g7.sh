#!/usr/bin/env bash
# S2-B10 / G7 — installed-distribution acceptance harness for core.archiveArtifacts.
#
# Runs the FOUR executable scenarios in this directory against the REAL installed
# CLI distribution (not an in-process coordinator), through the registry-only
# spine, and archives every raw output plus its sha256.
#
# Re-runnable and read-only with respect to the repository: all mutable state
# lives under $G7_WS (default /tmp/ar-g7-ws). Evidence is written to this
# directory (raw/) and returned as JSON on stdout.
#
# Usage:  ./run-g7.sh            # full run, JSON summary on stdout
#         ./run-g7.sh --keep     # keep $G7_WS after the run (default: also kept)
#
# Result truth = the emitted events + the on-disk filesystem effects, never the
# console wording. Every invocation is wrapped in `timeout` (V2 rule 4).

set -Eeuo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../../../.." && pwd)"
BIN="$ROOT/v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application"
DIST_JAR="$ROOT/v2/pipeline-application/build/install/pipeline-application/lib/pipeline-application-0.1.0-SNAPSHOT.jar"
WS="${G7_WS:-/tmp/ar-g7-ws}"
RAW="$HERE/raw"
BUDGET=600

[[ -x "$BIN" ]] || { echo "FATAL: installed binary not found at $BIN (run installDist)" >&2; exit 2; }

rm -rf "$WS"; mkdir -p "$WS" "$RAW"

sha256() { sha256sum "$1" | cut -d' ' -f1; }

run_scenario() {
    local tag="$1" script="$2"
    local db="$WS/${tag}.db" ctrl="$WS/ctrl-${tag}"
    local rc=0
    timeout "$BUDGET" "$BIN" run --db "$db" --control-root "$ctrl" "$script" \
        >"$RAW/${tag}-stdout.log" 2>"$RAW/${tag}-stderr.log" || rc=$?

    local runid="unknown"
    if compgen -G "$ctrl/last-run/*" >/dev/null; then
        runid="$(cat "$ctrl"/last-run/*)"
    fi

    if [[ -f "$db" ]]; then
        timeout "$BUDGET" "$BIN" events --db "$db" "$runid" \
            >"$RAW/${tag}-events.jsonl" 2>"$RAW/${tag}-events.err" || true
    fi

    # Filesystem truth: archived artefacts retention tree + retained workspace.
    if [[ -d "$ctrl/artefacts" ]]; then
        (cd "$ctrl" && find artefacts -type f | LC_ALL=C sort | xargs -r sha256sum) \
            >"$RAW/${tag}-archived-files.sha256" 2>/dev/null || true
    else
        : >"$RAW/${tag}-archived-files.sha256"
    fi
    if [[ -d "$ctrl/workspace" ]]; then
        (cd "$ctrl" && find workspace -type f | LC_ALL=C sort) \
            >"$RAW/${tag}-workspace-files.txt" 2>/dev/null || true
    else
        : >"$RAW/${tag}-workspace-files.txt"
    fi

    echo "$rc"    >"$RAW/${tag}-exit.txt"
    echo "$runid" >"$RAW/${tag}-runid.txt"
    echo "[$tag] exit=$rc runId=$runid"
}

echo "binary     : $BIN" >&2
echo "dist jar   : $(sha256 "$DIST_JAR")" >&2
echo "expected s1: $(seq 1 2000 | sha256sum | cut -d' ' -f1)  (seq 1 2000)" >&2
echo "workspace  : $WS" >&2
echo >&2

printf '%s\n' "$BIN"       >"$RAW/binary.txt"
printf '%s\n' "$(sha256 "$DIST_JAR")" >"$RAW/dist-jar-sha256.txt"

run_scenario "ar-g7-01" "$HERE/ar-g7-01-nonempty-byte-identical.pipeline.kts"
run_scenario "ar-g7-02" "$HERE/ar-g7-02-empty-match-fail.pipeline.kts"
run_scenario "ar-g7-03" "$HERE/ar-g7-03-empty-match-allow.pipeline.kts"
run_scenario "ar-g7-04" "$HERE/ar-g7-04-excludes.pipeline.kts"

# Absence probe: the deleted legacy dispatcher must be gone from source AND from
# every jar in the installed distribution, with a positive control proving the
# probe actually looks inside jars.
ABSENCE="$RAW/absence-probe.txt"
{
    echo "== legacy archiveArtifacts dispatcher in SOURCE (expect: none) =="
    find "$ROOT/v2" -path '*/src/main/*' -name 'CanonicalArchiveArtifacts*' -print 2>/dev/null || true
    echo "== ...in src/test (expect: none active) =="
    find "$ROOT/v2" -path '*/src/test/*' -name 'CanonicalArchiveArtifacts*' -print 2>/dev/null || true
    echo "== per-jar scan of the installed distribution =="
    total=0; hits=0; control=0
    while IFS= read -r jar; do
        total=$((total + 1))
        names="$(unzip -Z1 "$jar" 2>/dev/null || true)"
        if grep -q 'CanonicalArchiveArtifactsNodeDispatcher' <<<"$names"; then
            hits=$((hits + 1))
            echo "  LEGACY PRESENT: $(basename "$jar")"
        fi
        if grep -q 'CoreArchiveArtifactsStep' <<<"$names"; then
            control=$((control + 1))
            echo "  registry control present: $(basename "$jar")"
        fi
    done < <(find "$ROOT/v2/pipeline-application/build/install/pipeline-application/lib" -name '*.jar' | LC_ALL=C sort)
    echo "jars_scanned=$total legacy_jar_hits=$hits registry_control_jars=$control"
} >"$ABSENCE" 2>&1
grep -E '^(jars_scanned|  LEGACY PRESENT)' "$ABSENCE" >&2

# Manifest of every raw artefact + every scenario source.
( cd "$HERE" && { find raw -type f | LC_ALL=C sort | xargs -r sha256sum; \
                  find . -maxdepth 1 -name '*.pipeline.kts' | LC_ALL=C sort | xargs -r sha256sum; } ) \
    >"$RAW/../g7-raw-sha256.txt"

echo >&2
echo "raw manifest: $HERE/g7-raw-sha256.txt" >&2
echo "raw outputs : $RAW" >&2
