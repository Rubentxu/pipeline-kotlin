#!/usr/bin/env bash
# verify-external-compatibility.sh — the external-consumer gate, run BEFORE the tag is cut.
#
# The question it answers, with published artifacts on both sides:
#   "Can a consumer OUTSIDE this repository build a plugin against the SDK this
#    release publishes, and load it into the distribution this release publishes?"
#
# Why it is neither a scenario in examples/ nor a unit test:
#   - the scenarios in examples/ run the DEVELOPMENT distribution built by
#     installDist; this check consumes PUBLISHED artifacts, so it cannot be one
#     of them;
#   - a unit test may not shell out, and this check runs a Gradle build, a CLI
#     and a JVM.
# It is therefore a RELEASE gate: scripts/release-pipelinek.sh runs it before
# `git tag -a`, and a red row stops the publication.
#
# The rows, each of them with a real SDK and the candidate's own bytes:
#   A  the candidate's own published SDK      -> the plugin is ADMITTED, its step RUNS
#   B  the previously published SDK, same line -> the plugin is ADMITTED, its step RUNS
#   C  a published SDK from an older line      -> the plugin is REFUSED, typed, exit 2
# Row C is not a wish, it is the compatibility contract the product itself
# publishes: a plugin declares [sdk, nextMinor) and admission compares that
# against the running version, so a plugin built against 0.89.0 is refused by a
# 0.90.0 host. The check asserts that refusal instead of pretending it away,
# and row B states the other half of the same fact: within a line, the
# previously published SDK still loads.
#
# What the check does NOT do: it does not rebuild the candidate, it does not
# touch the repository's build outputs (the plugin is built in an isolated copy
# of the plugin sources), and it does not publish anything. It reads bytes and
# runs them.
#
# Usage:
#   verify-external-compatibility.sh --candidate-zip <path> --candidate-sdk-zip <path> \
#       --candidate-train <train> [--baseline-tag <tag>] [--older-tag <tag>] [--out <file>]
#
# --baseline-tag and --older-tag default to the most recent published release on
# the same line and on a different line respectively. A row whose baseline does
# not exist is SKIPPED with its reason stated; a row whose baseline exists but
# cannot be downloaded or verified is FAILED. The difference matters: "there is
# nothing to compare against" is a fact, "the comparison could not be made" is
# a broken instrument.

set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"

CANDIDATE_ZIP=""
CANDIDATE_SDK_ZIP=""
CANDIDATE_TRAIN=""
BASELINE_TAG=""
OLDER_TAG=""
OUT_FILE=""

usage() {
    sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 2
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --candidate-zip)    CANDIDATE_ZIP="${2:-}"; shift 2 ;;
        --candidate-sdk-zip) CANDIDATE_SDK_ZIP="${2:-}"; shift 2 ;;
        --candidate-train)  CANDIDATE_TRAIN="${2:-}"; shift 2 ;;
        --baseline-tag)     BASELINE_TAG="${2:-}"; shift 2 ;;
        --older-tag)        OLDER_TAG="${2:-}"; shift 2 ;;
        --out)              OUT_FILE="${2:-}"; shift 2 ;;
        -h|--help)          usage ;;
        *)
            printf 'verify-external-compatibility: unknown argument %s\n' "$1" >&2
            usage
            ;;
    esac
done

for required in CANDIDATE_ZIP CANDIDATE_SDK_ZIP CANDIDATE_TRAIN; do
    if [[ -z "${!required}" ]]; then
        printf 'verify-external-compatibility: --%s is required\n' \
            "$(printf '%s' "$required" | tr '[:upper:]' '[:lower:]' | tr '_' '-')" >&2
        exit 2
    fi
done

for artefact in "$CANDIDATE_ZIP" "$CANDIDATE_SDK_ZIP"; do
    if [[ ! -f "$artefact" ]]; then
        printf 'verify-external-compatibility: %s does not exist\n' "$artefact" >&2
        exit 2
    fi
done

PLUGIN_DIR="$repo_root/examples/example-uppercase-plugin"
if [[ ! -d "$PLUGIN_DIR/src" ]]; then
    printf 'verify-external-compatibility: the external plugin sources are missing at %s\n' \
        "$PLUGIN_DIR" >&2
    exit 2
fi

# The work area. Everything bulky lives here and is deleted when the run ends,
# successful or not; only the compact verdict survives, and only if the caller
# asked for it.
WORK="$(mktemp -d "${TMPDIR:-/tmp}/pipelinek-extcompat.XXXXXX")"
cleanup() {
    case "$WORK" in
        /tmp/*|/var/tmp/*|"${TMPDIR:-/tmp}"/*) rm -rf "$WORK" ;;
        *) printf 'verify-external-compatibility: refusing to delete %s\n' "$WORK" >&2 ;;
    esac
}
trap cleanup EXIT

STARTED_AT="$(date +%s)"

log() { printf 'external-compat: %s\n' "$*"; }

# ----------------------------------------------------------------------------
# 1. The candidate's own bytes: the distribution the release is about to
#    publish, and the SDK that release is about to publish.
# ----------------------------------------------------------------------------

DIST_ROOT="$WORK/dist"
mkdir -p "$DIST_ROOT"
unzip -q "$CANDIDATE_ZIP" -d "$DIST_ROOT"

CLI="$(find "$DIST_ROOT" -maxdepth 3 -type f -path '*/bin/pipelinek' | head -1)"
if [[ -z "$CLI" ]]; then
    printf 'verify-external-compatibility: the candidate distribution has no bin/pipelinek\n' >&2
    exit 1
fi
DIST_HOME="$(cd -- "$(dirname -- "$CLI")/.." && pwd)"

# The distribution bundles the example plugin, and that bundle would make the
# check measure nothing: the operator's artifact and the bundled copy would
# carry the same identity, and the runtime either deduplicates them by bytes
# (the operator's copy never runs) or refuses the whole composition for a
# duplicate identity. So the bundled copy is moved out of lib/ and the run
# below has exactly ONE possible provider of the plugin's step: the artifact an
# external consumer built from a published SDK. The bytes are not destroyed,
# they are kept next to the run as evidence of what was removed.
BUNDLED_DIR="$DIST_HOME/lib"
mkdir -p "$WORK/bundled-removed"
shopt -s nullglob
for bundled in "$BUNDLED_DIR"/example-uppercase-plugin-*.jar; do
    mv "$bundled" "$WORK/bundled-removed/"
done
shopt -u nullglob
if [[ ! -f "$WORK/bundled-removed/example-uppercase-plugin-0.1.0.jar" ]]; then
    log "note: the candidate distribution did not bundle the example plugin; nothing to remove"
fi

# The premise of every row, ASSERTED rather than assumed: the only artifact
# that can provide the plugin's step is the one the external consumer built.
# If a future distribution keeps the bundle somewhere else, this refuses rather
# than measuring the bundle and calling it the consumer's artifact.
if compgen -G "$DIST_HOME/lib/example-uppercase-plugin-*.jar" > /dev/null; then
    printf 'verify-external-compatibility: the candidate distribution still bundles the example plugin\n' >&2
    printf '    (%s). The rows below would measure the BUNDLE, not the artifact an external\n' \
        "$DIST_HOME/lib" >&2
    printf '    consumer built, so this check refuses instead of reporting a green row about\n' >&2
    printf '    the wrong artifact.\n' >&2
    exit 1
fi

# ----------------------------------------------------------------------------
# 2. The SDKs. One of them is the candidate's; the others are downloaded from
#    the releases that already exist, and every downloaded archive is verified
#    against the digest authority that release published.
# ----------------------------------------------------------------------------

sdk_version_from_layout() {
    # The archive states its own version in its Maven layout; the tag is only
    # a name for it. Reading the version from the artifact is what keeps the
    # check honest when a release is misnamed.
    local repo_dir="$1"
    local domain
    domain="$(find "$repo_dir" -maxdepth 6 -type d -path '*/dev/rubentxu/pipeline/v2/pipeline-domain' | head -1)"
    if [[ -z "$domain" ]]; then
        printf ''
        return 1
    fi
    find "$domain" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | head -1
}

download_published_sdk() {
    # $1 = release tag, $2 = destination directory.
    # Prints the extracted SDK repository path. Fails when the release cannot
    # be reached, when it carries no SDK, or when the archive does not match
    # the digest that release published.
    local tag="$1" dest="$2"
    local archive sums expected actual

    mkdir -p "$dest"
    if ! gh release download "$tag" --pattern 'pipelinek-*.sdk.zip' --dir "$dest" --clobber \
        >"$dest/download.log" 2>&1; then
        printf 'verify-external-compatibility: could not download the SDK of %s\n' "$tag" >&2
        sed 's/^/    /' "$dest/download.log" >&2
        return 1
    fi
    archive="$(find "$dest" -maxdepth 1 -name 'pipelinek-*.sdk.zip' | head -1)"
    if [[ -z "$archive" ]]; then
        printf 'verify-external-compatibility: %s published no SDK archive\n' "$tag" >&2
        return 1
    fi

    if ! gh release download "$tag" --pattern 'SHA256SUMS' --dir "$dest" --clobber \
        >>"$dest/download.log" 2>&1; then
        printf 'verify-external-compatibility: %s published no SHA256SUMS to verify the SDK against\n' \
            "$tag" >&2
        return 1
    fi
    sums="$dest/SHA256SUMS"

    expected="$(awk -v name="$(basename "$archive")" '$2 == name { print $1 }' "$sums")"
    actual="$(sha256sum "$archive" | awk '{ print $1 }')"
    if [[ -z "$expected" ]]; then
        printf 'verify-external-compatibility: the published digest authority of %s does not cover %s\n' \
            "$tag" "$(basename "$archive")" >&2
        return 1
    fi
    if [[ "$expected" != "$actual" ]]; then
        printf 'verify-external-compatibility: %s has digest %s but %s published %s\n' \
            "$(basename "$archive")" "$actual" "$tag" "$expected" >&2
        return 1
    fi

    mkdir -p "$dest/repo"
    unzip -q "$archive" -d "$dest/repo"
    printf '%s' "$dest/repo"
}

# ----------------------------------------------------------------------------
# 3. The rows.
# ----------------------------------------------------------------------------

declare -a ROW_NAMES=()
declare -a ROW_SOURCES=()
declare -a ROW_SDK_VERSIONS=()
declare -a ROW_VERDICTS=()
declare -a ROW_DETAILS=()

# build_plugin <row> <sdk repository> <sdk version> -> prints the plugin jar
build_plugin() {
    local row="$1" sdk_repo="$2" sdk_version="$3"
    local build_dir="$WORK/build-$row" log_file="$WORK/build-$row.log"

    mkdir -p "$build_dir"
    cp -r "$PLUGIN_DIR/src" "$build_dir/"
    cp "$PLUGIN_DIR/build.gradle.kts" "$PLUGIN_DIR/settings.gradle.kts" "$build_dir/"

    # The plugin is built in an ISOLATED copy of the sources, never in the
    # repository tree: the repository's own build output is the PRODUCT's
    # plugin, and a check that overwrote it would leave the tree claiming one
    # thing and shipping another. An isolated copy also cannot reuse the
    # classes of an earlier build, so what is measured is a plugin compiled
    # against the SDK this row names.
    if ! "$repo_root/pipelinek/gradlew" --no-daemon -p "$build_dir" jar \
        -PsdkRepo="$sdk_repo" -PsdkVersion="$sdk_version" >"$log_file" 2>&1; then
        printf 'verify-external-compatibility: the plugin could not be built against SDK %s\n' \
            "$sdk_version" >&2
        tail -20 "$log_file" | sed 's/^/    /' >&2
        return 1
    fi
    printf '%s' "$build_dir/build/libs/example-uppercase-plugin-0.1.0.jar"
}

# run_host <row> <plugin jar> -> writes stdout/stderr under $WORK/run-<row>,
# prints the exit code.
run_host() {
    local row="$1" plugin_jar="$2"
    local run_root="$WORK/run-$row"
    mkdir -p "$run_root/control"

    cat > "$run_root/uppercase.pipeline.kts" <<'KTS'
import example.uppercase.uppercase

pipeline {
    stages {
        stage("External") {
            uppercase("hello")
        }
    }
}
KTS

    local code=0
    (
        cd "$run_root" &&
        JAVA_OPTS="-Dorg.sqlite.tmpdir=$run_root" \
            "$CLI" run --isolated --db "$run_root/events.db" \
            --control-root "$run_root/control" --plugin-jar "$plugin_jar" \
            "$run_root/uppercase.pipeline.kts"
    ) >"$run_root/stdout" 2>"$run_root/stderr" || code=$?
    printf '%s' "$code"
}

# expect_admitted <row> <plugin jar>
# The guarantee: the artifact an external consumer built from a published SDK
# is admitted into the composition AND its step really runs. Both halves are
# asserted, because either alone is satisfiable by a run that did nothing: a
# composition that admits nothing reports no plugin, and a plugin that is
# admitted but never executed leaves the stage unfinished.
expect_admitted() {
    local row="$1" plugin_jar="$2"
    local run_root="$WORK/run-$row"
    local code
    code="$(run_host "$row" "$plugin_jar")"

    if [[ "$code" != "0" ]]; then
        ROW_VERDICTS+=("FAIL")
        ROW_DETAILS+=("exit=$code; $(grep -m1 'admission refused' "$run_root/stderr" || true)")
        return 1
    fi
    if ! grep -q 'Discovered external Step plugins: .*example\.uppercase' "$run_root/stderr"; then
        ROW_VERDICTS+=("FAIL")
        ROW_DETAILS+=("the composition did not report the plugin as discovered")
        return 1
    fi
    if ! grep -q 'external/registrystep-0 finished -> success' "$run_root/stdout"; then
        ROW_VERDICTS+=("FAIL")
        ROW_DETAILS+=("the plugin's step did not run to success")
        return 1
    fi
    ROW_VERDICTS+=("PASS")
    ROW_DETAILS+=("admitted; the step ran to success")
    return 0
}

# expect_refused <row> <plugin jar> <declared range>
# The guarantee: the compatibility contract the product publishes is enforced
# at admission, with a TYPED refusal and a non-zero exit. A run that failed for
# another reason (a broken jar, a missing class) is not this guarantee.
expect_refused() {
    local row="$1" plugin_jar="$2" declared="$3"
    local run_root="$WORK/run-$row"
    local code
    code="$(run_host "$row" "$plugin_jar")"

    if [[ "$code" != "2" ]]; then
        ROW_VERDICTS+=("FAIL")
        ROW_DETAILS+=("exit=$code; a refusal must exit 2")
        return 1
    fi
    if ! grep -q 'Plugin admission refused for' "$run_root/stderr"; then
        ROW_VERDICTS+=("FAIL")
        ROW_DETAILS+=("the run failed without an admission refusal")
        return 1
    fi
    if ! grep -q 'IncompatibleApiRange' "$run_root/stderr"; then
        ROW_VERDICTS+=("FAIL")
        ROW_DETAILS+=("the refusal was not the typed API-range refusal: $(grep -m1 'admission refused' "$run_root/stderr" || true)")
        return 1
    fi
    if ! grep -qF "declared=$declared" "$run_root/stderr"; then
        ROW_VERDICTS+=("FAIL")
        ROW_DETAILS+=("the refusal did not name the declared range $declared")
        return 1
    fi
    ROW_VERDICTS+=("PASS")
    ROW_DETAILS+=("refused with IncompatibleApiRange($declared)")
    return 0
}

record_row() {
    ROW_NAMES+=("$1")
    ROW_SOURCES+=("$2")
    ROW_SDK_VERSIONS+=("$3")
}

# ----------------------------------------------------------------------------
# 4. Which published releases to compare against.
# ----------------------------------------------------------------------------

train_of_tag() {
    local tag="${1#v}"
    printf '%s' "${tag%-rc[0-9]*}"
}

if [[ -z "$BASELINE_TAG" || -z "$OLDER_TAG" ]]; then
    if ! command -v gh >/dev/null; then
        printf 'verify-external-compatibility: gh is required to find the published releases\n' >&2
        exit 2
    fi
    RELEASE_TAGS="$(gh release list --limit 100 --json tagName --jq '.[].tagName' 2>/dev/null || true)"
    if [[ -z "$RELEASE_TAGS" ]]; then
        printf 'verify-external-compatibility: no published release could be listed\n' >&2
        exit 1
    fi
    if [[ -z "$BASELINE_TAG" ]]; then
        while IFS= read -r candidate_tag; do
            if [[ "$(train_of_tag "$candidate_tag")" == "$CANDIDATE_TRAIN" ]]; then
                BASELINE_TAG="$candidate_tag"
                break
            fi
        done <<< "$RELEASE_TAGS"
    fi
    if [[ -z "$OLDER_TAG" ]]; then
        while IFS= read -r candidate_tag; do
            if [[ "$(train_of_tag "$candidate_tag")" != "$CANDIDATE_TRAIN" ]]; then
                OLDER_TAG="$candidate_tag"
                break
            fi
        done <<< "$RELEASE_TAGS"
    fi
fi

log "candidate train=$CANDIDATE_TRAIN baseline=${BASELINE_TAG:-<none>} older=${OLDER_TAG:-<none>}"

# ----------------------------------------------------------------------------
# 5. Row A: the candidate's own published SDK.
# ----------------------------------------------------------------------------

record_row "A" "candidate" "$CANDIDATE_TRAIN"
CANDIDATE_SDK_REPO="$WORK/sdk-candidate"
mkdir -p "$CANDIDATE_SDK_REPO"
unzip -q "$CANDIDATE_SDK_ZIP" -d "$CANDIDATE_SDK_REPO"
CANDIDATE_SDK_VERSION="$(sdk_version_from_layout "$CANDIDATE_SDK_REPO" || true)"
if [[ -z "$CANDIDATE_SDK_VERSION" ]]; then
    ROW_VERDICTS+=("FAIL")
    ROW_DETAILS+=("the candidate SDK archive states no version in its layout")
else
    ROW_SDK_VERSIONS[${#ROW_SDK_VERSIONS[@]}-1]="$CANDIDATE_SDK_VERSION"
    if [[ "$CANDIDATE_SDK_VERSION" != "$CANDIDATE_TRAIN" ]]; then
        ROW_VERDICTS+=("FAIL")
        ROW_DETAILS+=("the candidate SDK states version $CANDIDATE_SDK_VERSION but the candidate train is $CANDIDATE_TRAIN")
    else
        PLUGIN_JAR="$(build_plugin "A" "$CANDIDATE_SDK_REPO" "$CANDIDATE_SDK_VERSION" || true)"
        if [[ -z "$PLUGIN_JAR" || ! -f "$PLUGIN_JAR" ]]; then
            ROW_VERDICTS+=("FAIL")
            ROW_DETAILS+=("the plugin could not be built against the candidate SDK")
        else
            expect_admitted "A" "$PLUGIN_JAR" || true
        fi
    fi
fi

# ----------------------------------------------------------------------------
# 6. Row B: the SDK this line published BEFORE, consumed by the new host.
#    This is the row that answers "does what was published still work?".
# ----------------------------------------------------------------------------

if [[ -z "$BASELINE_TAG" ]]; then
    record_row "B" "<none>" "<none>"
    ROW_VERDICTS+=("SKIP")
    ROW_DETAILS+=("no earlier release exists on line $CANDIDATE_TRAIN to compare against")
else
    record_row "B" "$BASELINE_TAG" "<pending>"
    BASELINE_SDK_REPO="$(download_published_sdk "$BASELINE_TAG" "$WORK/sdk-baseline" || true)"
    if [[ -z "$BASELINE_SDK_REPO" ]]; then
        ROW_VERDICTS+=("FAIL")
        ROW_DETAILS+=("the SDK of $BASELINE_TAG could not be downloaded or verified")
    else
        BASELINE_SDK_VERSION="$(sdk_version_from_layout "$BASELINE_SDK_REPO" || true)"
        ROW_SDK_VERSIONS[${#ROW_SDK_VERSIONS[@]}-1]="$BASELINE_SDK_VERSION"
        if [[ -z "$BASELINE_SDK_VERSION" ]]; then
            ROW_VERDICTS+=("FAIL")
            ROW_DETAILS+=("the SDK of $BASELINE_TAG states no version in its layout")
        else
            PLUGIN_JAR="$(build_plugin "B" "$BASELINE_SDK_REPO" "$BASELINE_SDK_VERSION" || true)"
            if [[ -z "$PLUGIN_JAR" || ! -f "$PLUGIN_JAR" ]]; then
                ROW_VERDICTS+=("FAIL")
                ROW_DETAILS+=("the plugin could not be built against the SDK of $BASELINE_TAG")
            else
                expect_admitted "B" "$PLUGIN_JAR" || true
            fi
        fi
    fi
fi

# ----------------------------------------------------------------------------
# 7. Row C: a published SDK from an older line. The product refuses it, and
#    the refusal is the contract, not a defect.
# ----------------------------------------------------------------------------

if [[ -z "$OLDER_TAG" ]]; then
    record_row "C" "<none>" "<none>"
    ROW_VERDICTS+=("SKIP")
    ROW_DETAILS+=("no release exists on an older line to compare against")
else
    record_row "C" "$OLDER_TAG" "<pending>"
    OLDER_SDK_REPO="$(download_published_sdk "$OLDER_TAG" "$WORK/sdk-older" || true)"
    if [[ -z "$OLDER_SDK_REPO" ]]; then
        ROW_VERDICTS+=("FAIL")
        ROW_DETAILS+=("the SDK of $OLDER_TAG could not be downloaded or verified")
    else
        OLDER_SDK_VERSION="$(sdk_version_from_layout "$OLDER_SDK_REPO" || true)"
        ROW_SDK_VERSIONS[${#ROW_SDK_VERSIONS[@]}-1]="$OLDER_SDK_VERSION"
        if [[ -z "$OLDER_SDK_VERSION" ]]; then
            ROW_VERDICTS+=("FAIL")
            ROW_DETAILS+=("the SDK of $OLDER_TAG states no version in its layout")
        else
            # The range the plugin declares is derived from the SDK it was built
            # against: [that version, the next minor). It is read from the
            # artifact, not assumed, because the whole point of this row is
            # what the product publishes.
            DECLARED_RANGE="$(unzip -p "$(build_plugin "C" "$OLDER_SDK_REPO" "$OLDER_SDK_VERSION" || true)" \
                META-INF/pipelinek/plugin-manifest.json 2>/dev/null |
                python3 -c 'import json,sys; print(json.load(sys.stdin).get("apiRange",""))' 2>/dev/null || true)"
            if [[ -z "$DECLARED_RANGE" ]]; then
                ROW_VERDICTS+=("FAIL")
                ROW_DETAILS+=("the plugin built against the SDK of $OLDER_TAG declares no range")
            else
                PLUGIN_JAR="$WORK/build-C/build/libs/example-uppercase-plugin-0.1.0.jar"
                expect_refused "C" "$PLUGIN_JAR" "$DECLARED_RANGE" || true
            fi
        fi
    fi
fi

# ----------------------------------------------------------------------------
# 8. The verdict. One line, and it says what ran and what did not.
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
    printf 'external-compatibility: %s rows=%s candidate=%s baseline=%s older=%s elapsed=%ss\n' \
        "$VERDICT" "$SUMMARY_ROWS" "$CANDIDATE_TRAIN" "${BASELINE_TAG:-<none>}" \
        "${OLDER_TAG:-<none>}" "$ELAPSED"
    for index in "${!ROW_NAMES[@]}"; do
        printf '  row %s source=%s sdk=%s verdict=%s %s\n' \
            "${ROW_NAMES[$index]}" "${ROW_SOURCES[$index]}" "${ROW_SDK_VERSIONS[$index]}" \
            "${ROW_VERDICTS[$index]}" "${ROW_DETAILS[$index]}"
    done
} | tee "$WORK/verdict.txt"

if [[ -n "$OUT_FILE" ]]; then
    mkdir -p "$(dirname -- "$OUT_FILE")"
    cp "$WORK/verdict.txt" "$OUT_FILE"
fi

if [[ "$FAILED" != "0" ]]; then
    printf 'verify-external-compatibility: the external consumer check FAILED\n' >&2
    exit 1
fi

log "the external consumer check PASSED (rows: $SUMMARY_ROWS)"
