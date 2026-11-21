#!/usr/bin/env bash
# verify-external-consumer.sh — the isolated-consumer gate.
#
# WHAT IT ANSWERS, IN ONE SENTENCE
#
#   Can a program OUTSIDE this repository resolve the published SDK by its
#   coordinates, compile against it, and ask a running service a real question?
#
# WHICH SDK IT MEASURES
#
#   The one THIS release publishes. The release names the version, and the
#   gate refuses a repository that does not carry it: a gate that measured
#   whichever version it found first would certify a consumer running with an
#   SDK this release does not publish.
#
#   The repository the consumer resolves from is a COPY, and the copy carries
#   ONLY the version under test. The repository the publication lands in
#   accumulates: every release adds its artifacts beside the ones of the
#   releases before it, and a consumer that resolves from such a copy can end
#   up running the SDK of an earlier release.
#
# WHERE THE LAW LIVES
#
#   The version the consumer RESOLVED is compared with the version it ASKED
#   FOR inside the consumer's own build, because that is where the resolution
#   happens: a law written anywhere else would measure a classpath that no
#   consumer runs with. The digests are compared there too, and the gate
#   refuses a receipt that carries no version the consumer resolved.
#
# WHY IT IS A GATE AND NOT A TEST
#
#   The answer is about BYTES that leave this repository: the artifacts a
#   third party downloads and the program that downloads them. A unit test
#   cannot see either of them, and a test that resolved the SDK from the build
#   tree would be measuring the tree and not the publication. So this is a
#   release gate: it publishes, it resolves, it compiles and it RUNS a program
#   against a service this product hosts.
#
# THE ROWS, IN THE ORDER THEY ARE PAID
#
#   1  the SDK of THIS release, at the version the release names, published to
#      a TEMPORARY Maven repository that carries only that version
#   2  the consumer, in an ISOLATED COPY of its sources, resolved by
#      coordinates: the copy has no access to this tree's projects, so a
#      consumer that needed one would not build at all
#   3  the classpath the consumer runs with: no server, no provider, no
#      Jenkins, and every upstream class the SDK names
#   4  the bytes the consumer resolved: the ones the repository declares, at
#      the version the release names
#   5  a REAL question: the consumer asks a hosted service about a real run
#   6  the negatives: an empty repository, and a necessary dependency withdrawn
#
# WHY THE NEGATIVES ARE TWO AND NOT ONE
#
#   A consumer that compiles proves that SOMETHING resolved. The same consumer
#   against a repository with nothing in it proves that the resolution is what
#   made it compile. The same consumer against a repository whose CLIENT
#   artifact is gone proves something else: that the artifact the consumer
#   needs is a real dependency and not decoration. One of the two negatives
#   says the repository matters; the other says the dependency does. A gate
#   that paid only one of them would pass against a consumer that resolved
#   nothing it needed.
#
# WHERE THE SERVICE COMES FROM
#
#   From this product's own `serve` verb, over the product's own launcher, and
#   the run it answers about is a run this product's own `run` verb produced.
#   The consumer is the only party that lives outside: it resolves the SDK
#   from a repository, and it talks to the service over a socket.
#
# WHAT IT DOES NOT DO
#
#   It publishes nothing anywhere. The repository the consumer resolves from is
#   a directory under a temporary directory, and the REMOTE publication is a
#   decision somebody else makes with an authorization this script does not
#   carry.
#
# WHAT IT LEAVES BEHIND
#
#   The receipt, and nothing else. The temporary repository the consumer
#   resolved from goes away with the run, because what is used is released:
#   a gate that leaves a directory behind on every run is a leak, and leaks
#   accumulate. The evidence of what the consumer resolved from travels in
#   the receipt, which is a small file the reader decides where to put: the
#   version it resolved, the version the release named, the versions the copy
#   it resolved from carried, the repository it resolved from, how many
#   artifacts matched the digests that repository declares, and the digest of
#   the client artifact the consumer ran with.
#
# Usage:
#   verify-external-consumer.sh --expect-version <v> [--sdk-repo <dir>]
#       [--out <file>]
#
# --expect-version names the version of the SDK the consumer resolves. It is
# required: the release declares it, and a version a gate DISCOVERS in the
# repository is a version the repository happens to carry.
#
# --sdk-repo names a Maven repository that already holds the SDK. Without it,
# the SDK of this revision is published into the build tree and copied into the
# temporary repository the consumer resolves from.
#
# Exit codes: 0 = the consumer resolved, compiled and asked; 1 = refused;
# 2 = usage error.

set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"

SDK_REPO=""
OUT_FILE=""
EXPECT_VERSION=""

usage() {
    # The header is printed as it is written, up to the first line that is
    # not a comment. A hardcoded line range goes stale the moment the text
    # grows, and a stale range truncates the instructions in silence.
    awk 'NR > 1 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
    exit 2
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --sdk-repo) SDK_REPO="${2:-}"; shift 2 ;;
        --expect-version) EXPECT_VERSION="${2:-}"; shift 2 ;;
        --out)      OUT_FILE="${2:-}"; shift 2 ;;
        -h|--help)  usage ;;
        *)
            printf 'verify-external-consumer: unknown argument %s\n' "$1" >&2
            usage
            ;;
    esac
done

if [[ -z "$EXPECT_VERSION" ]]; then
    printf 'verify-external-consumer: --expect-version is required: the release names the SDK the consumer resolves\n' >&2
    usage
fi

CONSUMER_SOURCES="$repo_root/examples/example-service-client"
if [[ ! -d "$CONSUMER_SOURCES/src" ]]; then
    printf 'verify-external-consumer: the consumer sources are missing at %s\n' \
        "$CONSUMER_SOURCES" >&2
    exit 2
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/pipelinek-consumer.XXXXXX")"
# The temporary repository lives in a directory of its OWN, and that directory
# goes away with the run: what is used is released. The evidence of what the
# consumer resolved from travels in the receipt, which is a small file the
# reader decides where to put.
EVIDENCE="$(mktemp -d "${TMPDIR:-/tmp}/pipelinek-sdk-repo.XXXXXX")"
SERVICE_PID=""

stop_service() {
    if [[ -n "$SERVICE_PID" ]] && kill -0 "$SERVICE_PID" 2>/dev/null; then
        kill "$SERVICE_PID" 2>/dev/null || true
        wait "$SERVICE_PID" 2>/dev/null || true
    fi
    SERVICE_PID=""
}

cleanup() {
    stop_service
    for directory in "$WORK" "$EVIDENCE"; do
        case "$directory" in
            /tmp/*|/var/tmp/*|"${TMPDIR:-/tmp}"/*) rm -rf "$directory" ;;
            *) printf 'verify-external-consumer: refusing to delete %s\n' "$directory" >&2 ;;
        esac
    done
}
trap cleanup EXIT

fail() {
    printf 'verify-external-consumer: %s\n' "$1" >&2
    printf 'CONSUMER: FAIL\n'
    exit 1
}

# One row, one shape: a label and its measurement, separated by a single
# space. The rows are read by a program, and a program that reads a row with
# a column of spaces in it reads spaces where the measurement should be.
row() {
    printf 'consumer: %s\n' "$1"
}

# ── 1. the SDK of this release, at the version it names, in a temporary repository ─
#
# The publication is the one the build already declares: the task that publishes
# the published contracts into the build tree. The copy is what makes the
# repository TEMPORARY: the consumer resolves from a directory that belongs to
# this run and to nothing else, and the build tree keeps whatever it had.
#
# The remote publication is NOT here. It is a decision with an authorization,
# and a script that cannot ask for one does not take it.
if [[ -n "$SDK_REPO" ]]; then
    if [[ ! -d "$SDK_REPO" ]]; then
        printf 'verify-external-consumer: %s is not a directory\n' "$SDK_REPO" >&2
        exit 2
    fi
    SOURCE_REPO="$(cd -- "$SDK_REPO" && pwd)"
else
    if ! "$repo_root/pipelinek/gradlew" --console=plain -p "$repo_root/pipelinek" \
        :publishSdkForExternalPlugin > "$WORK/publish.log" 2>&1; then
        tail -30 "$WORK/publish.log" >&2
        fail "the SDK of this revision could not be published"
    fi
    SOURCE_REPO="$repo_root/pipelinek/build/sdk-repo"
fi

# The repository the consumer resolves from is a COPY, and the copy carries
# ONLY the version under test.
#
# The repository the publication lands in accumulates: every release adds its
# artifacts beside the ones of the releases before it, and nothing empties it
# between them. A consumer that resolves from a copy of such a repository can
# end up running the SDK of an earlier release — the bytes of a release that
# does not publish what this one publishes. The copy is therefore BUILT with
# the version under test and not repaired afterwards: the artifact of an
# earlier release is not in it, not even when the resolution is not pinned by
# the resolution document.
REPO="$EVIDENCE/repository"

# The groups the SDK is published under. The publication before the rename
# carried another one, and both are looked for: a reader that knew only the
# current one would answer "there is nothing here" about a repository of a
# previous release, and that answer would be a lie about the repository.
GROUP_PATHS=("dev/rubentxu/pipelinek" "dev/rubentxu/pipeline/v2")
GROUP_PATH=""
for candidate in "${GROUP_PATHS[@]}"; do
    if [[ -d "$SOURCE_REPO/$candidate" ]]; then
        GROUP_PATH="$candidate"
        break
    fi
done
[[ -n "$GROUP_PATH" ]] || fail "the repository carries no SDK group at all"

# The version under test: the one the release publishes. It is NAMED and not
# found, because a version found in the repository is a version the
# repository happens to carry, and the repository accumulates publications.
VERSION="$EXPECT_VERSION"

# The repository has to carry it. The refusal names the version, because the
# reader has to know which one is missing to go and look for it.
CLIENT_ARTIFACT="pipelinek-service-client"
RESOLUTION_DOCUMENT="pipelinek-sdk-bom"
if [[ ! -d "$SOURCE_REPO/$GROUP_PATH/$CLIENT_ARTIFACT/$VERSION" ]]; then
    fail "the repository carries no version $VERSION of the SDK the consumer needs"
fi
if [[ ! -d "$SOURCE_REPO/$GROUP_PATH/$RESOLUTION_DOCUMENT/$VERSION" ]]; then
    fail "the repository carries no version $VERSION of the resolution document the consumer resolves through"
fi
row "sdk repository $REPO"
row "sdk version $VERSION"

# The copy: every artifact of every group, at the version under test and at
# no other version. The metadata that lists the versions of an artifact is
# not copied either: a repository that advertises a version it does not carry
# answers a question about a publication that is not there.
copy_version_under_test() {
    local source="$1" target="$2" version="$3" group_path artifact_directory artifact_name
    for group_path in "${GROUP_PATHS[@]}"; do
        local group_source="$source/$group_path"
        [[ -d "$group_source" ]] || continue
        for artifact_directory in "$group_source"/*; do
            [[ -d "$artifact_directory" ]] || continue
            [[ -d "$artifact_directory/$version" ]] || continue
            artifact_name="$(basename -- "$artifact_directory")"
            mkdir -p "$target/$group_path/$artifact_name"
            cp -r "$artifact_directory/$version" "$target/$group_path/$artifact_name/"
        done
    done
}
copy_version_under_test "$SOURCE_REPO" "$REPO" "$VERSION"

# The copy carries ONE version, and the gate MEASURES it instead of assuming
# it: a copy that carried another version would be a copy the consumer could
# resolve an artifact of an earlier release from, and the receipt has to say
# what the consumer could resolve.
REPOSITORY_VERSIONS="$(
    for group_path in "${GROUP_PATHS[@]}"; do
        group_directory="$REPO/$group_path"
        [[ -d "$group_directory" ]] || continue
        find "$group_directory" -mindepth 2 -maxdepth 2 -type d -printf '%f\n' 2>/dev/null
    done | sort -u | tr '\n' ',' | sed 's/,$//'
)"
if [[ "$REPOSITORY_VERSIONS" != "$VERSION" ]]; then
    fail "the copy the consumer resolves from carries versions other than $VERSION: ${REPOSITORY_VERSIONS:-<none>}"
fi
row "sdk repository versions $REPOSITORY_VERSIONS"

# ── 2. the consumer, in an isolated copy ───────────────────────────────────────
#
# Three copies, one per build: the one that has to succeed, and the two whose
# failure is the point. Each copy is made from the sources BEFORE any of them
# is built, so no copy carries the build state of another one: a copy that
# found its own output up to date would not resolve anything, and a negative
# that passed for that reason would be a negative that measured nothing.
#
# The toolchain declaration travels with the copies because it belongs to the
# machine and not to the sources: a build that asks for a JDK the machine does
# not have fails before it reads a single source file.
copy_consumer() {
    local target="$1"
    mkdir -p "$target"
    cp -r "$CONSUMER_SOURCES/src" "$target/"
    cp "$CONSUMER_SOURCES/build.gradle.kts" "$CONSUMER_SOURCES/settings.gradle.kts" "$target/"
    cp "$repo_root/.tool-versions" "$target/"
}

CONSUMER="$WORK/consumer"
CONSUMER_EMPTY="$WORK/consumer-empty-repository"
CONSUMER_WITHDRAWN="$WORK/consumer-withdrawn-dependency"
copy_consumer "$CONSUMER"
copy_consumer "$CONSUMER_EMPTY"
copy_consumer "$CONSUMER_WITHDRAWN"

# The build of one copy, against one repository.
consumer_build() {
    local build_dir="$1" repository="$2"
    shift 2
    "$repo_root/pipelinek/gradlew" --console=plain -p "$build_dir" "$@" \
        -PsdkRepo="$repository" -PsdkVersion="$VERSION"
}

# ── 3 and 4. the classpath and the identity of the bytes ───────────────────────
#
# The two laws live in the consumer's own build, and the consumer's build is
# the thing that resolves: a law written anywhere else would measure a
# classpath that no consumer runs with.
#
# The refresh is here because the gate has to measure the repository as it is
# NOW: without it the resolution may answer with what a previous build
# remembered, and the gate would measure that instead of the repository.
MEASUREMENT="$WORK/identity-measurement.txt"
if ! consumer_build "$CONSUMER" "$REPO" --refresh-dependencies \
    -PidentityMeasurement="$MEASUREMENT" \
    installDist verifyRuntimeClasspath verifyResolvedIdentity > "$WORK/consumer.log" 2>&1; then
    tail -30 "$WORK/consumer.log" >&2
    fail "the consumer could not resolve the published SDK, compile against it and pass its own laws"
fi
sed -n 's/^consumer: /consumer: /p' "$WORK/consumer.log" | while IFS= read -r line; do
    printf '%s\n' "$line"
done

# ── 5. the real question ──────────────────────────────────────────────────────
#
# The service is this product's own, hosted by its own launcher over a socket
# that only this user may open, and the run it answers about is a run this
# product's own `run` verb produced. The consumer asks about that run with the
# typed client of the published SDK, and the answer it gets is the same
# observation the command line renders about the same run.
if ! "$repo_root/pipelinek/gradlew" --console=plain -p "$repo_root/pipelinek" \
    :pipeline-application:installDist > "$WORK/distribution.log" 2>&1; then
    tail -30 "$WORK/distribution.log" >&2
    fail "the distribution that hosts the service could not be built"
fi
LAUNCHER="$repo_root/pipelinek/pipeline-application/build/install/pipelinek/bin/pipelinek"
if [[ ! -x "$LAUNCHER" ]]; then
    fail "the distribution carries no launcher at $LAUNCHER"
fi

RUN_ROOT="$WORK/run"
mkdir -p "$RUN_ROOT/control"
cat > "$RUN_ROOT/greet.pipeline.kts" <<'PIPELINE'
pipeline {
    stages {
        stage("greet") {
            echo("the isolated consumer gate")
        }
    }
}
PIPELINE

if ! "$LAUNCHER" run --db "$RUN_ROOT/events.db" --control-root "$RUN_ROOT/control" \
    "$RUN_ROOT/greet.pipeline.kts" > "$WORK/run.log" 2>&1; then
    tail -30 "$WORK/run.log" >&2
    fail "the product could not run the pipeline the consumer will ask about"
fi
RUN_ID="$(sed -n 's/.*"runId":"\([^"]*\)".*/\1/p' "$WORK/run.log" | head -1)"
[[ -n "$RUN_ID" ]] || fail "the run published no identity, so there is no run to ask about"

# The socket lives in a directory only this user may enter: the service
# refuses a socket other processes could open, and that refusal is the
# product's own.
SOCKET_DIR="$WORK/service"
mkdir -p "$SOCKET_DIR"
chmod 700 "$SOCKET_DIR"
SOCKET="$SOCKET_DIR/service.sock"

"$LAUNCHER" serve --db "$RUN_ROOT/events.db" --control-root "$RUN_ROOT/control" \
    --socket "$SOCKET" > "$WORK/serve.log" 2>&1 &
SERVICE_PID=$!

socket_ready=0
for _ in $(seq 1 120); do
    if [[ -S "$SOCKET" && -f "$SOCKET_DIR/authorizations" ]]; then
        socket_ready=1
        break
    fi
    if ! kill -0 "$SERVICE_PID" 2>/dev/null; then
        break
    fi
    sleep 0.5
done
if [[ "$socket_ready" -ne 1 ]]; then
    tail -20 "$WORK/serve.log" >&2
    fail "the service did not come up on the socket the consumer has to reach"
fi

# The token the service wrote next to its socket: the same one any other
# consumer of the SDK presents.
TOKEN="$(cut -d' ' -f1 < "$SOCKET_DIR/authorizations")"
if [[ -z "$TOKEN" ]]; then
    fail "the service published no authorization for a client to present"
fi

CLIENT="$CONSUMER/build/install/example-service-client/bin/example-service-client"
if [[ ! -x "$CLIENT" ]]; then
    fail "the consumer was not installed at $CLIENT"
fi

if ! PIPELINEK_SERVICE_TOKEN="$TOKEN" "$CLIENT" "$SOCKET" "$RUN_ID" \
    > "$WORK/query.log" 2>&1; then
    cat "$WORK/query.log" >&2
    fail "the consumer could not ask the service about the run"
fi
cat "$WORK/query.log"

# The answer has to be an ANSWER and not the shape of one: the consumer that
# asked nothing prints neither of these lines, and the consumer that was
# refused prints them on the standard error and ends with a non-zero code.
grep -q '^QUERY version schema=[^ ]* product=[^ ]*$' "$WORK/query.log" \
    || fail "the consumer asked the service for no version"
grep -q "^QUERY run id=$RUN_ID state=[^ ]* outcome=[^ ]*$" "$WORK/query.log" \
    || fail "the consumer received no state for the run it asked about"
row "question the service answered about run $RUN_ID"

# ── 6. the negatives ──────────────────────────────────────────────────────────
#
# The empty repository: the same consumer, against a repository with nothing
# in it. It has to FAIL to resolve, because a consumer that compiles against
# anything proves nothing.
EMPTY_REPO="$WORK/empty-repository"
mkdir -p "$EMPTY_REPO"
if consumer_build "$CONSUMER_EMPTY" "$EMPTY_REPO" --refresh-dependencies installDist \
    > "$WORK/negative-empty.log" 2>&1; then
    fail "the consumer compiled against a repository with no artifacts; this gate cannot tell a working repository from a lucky one"
fi
row "empty repository the same consumer failed against it, as it must"

# The withdrawn dependency: the same repository with the CLIENT artifact gone.
# The dependency the consumer needs is a real one and not decoration, and the
# refusal names the artifact that is not there.
WITHDRAWN_REPO="$WORK/withdrawn-repository"
cp -r "$REPO" "$WITHDRAWN_REPO"
rm -rf "${WITHDRAWN_REPO:?}/$GROUP_PATH/pipelinek-service-client/$VERSION"
if [[ -d "$WITHDRAWN_REPO/$GROUP_PATH/pipelinek-service-client/$VERSION" ]]; then
    fail "the dependency could not be withdrawn from the repository"
fi
if consumer_build "$CONSUMER_WITHDRAWN" "$WITHDRAWN_REPO" --refresh-dependencies installDist \
    > "$WORK/negative-withdrawn.log" 2>&1; then
    fail "the consumer compiled with the client artifact withdrawn; the dependency it needs is not a real one"
fi
row "withdrawn dependency the same consumer failed with the client artifact withdrawn, as it must"

# ── the receipt ───────────────────────────────────────────────────────────────
#
# The receipt is what the run leaves behind, because the run leaves NOTHING
# else alive: the temporary repository goes away with it, and a reader that
# wants to know what the consumer resolved from reads the receipt.
#
# The receipt carries the MEASUREMENT and not a claim about it: how many
# artifacts matched the digests the repository declares, the digest of the
# client artifact the consumer ran with, and the version the consumer
# resolved. A receipt without them is refused before it is written: a receipt
# that says nothing measured is a receipt that reads like a green.
if [[ -n "$OUT_FILE" ]]; then
    MATCHED="$(sed -n 's/^artifacts_matched=//p' "$MEASUREMENT" | head -1)"
    CLIENT_DIGEST="$(sed -n 's/^client_digest=//p' "$MEASUREMENT" | head -1)"
    RESOLVED_VERSION="$(sed -n 's/^resolved_version=//p' "$MEASUREMENT" | head -1)"
    if [[ ! "$MATCHED" =~ ^[0-9]+$ ]] || [[ "$MATCHED" -le 0 ]]; then
        fail "the consumer resolved no artifact whose digest the repository declares; the receipt would say nothing measured"
    fi
    if [[ ! "$CLIENT_DIGEST" =~ ^[0-9a-f]{64}$ ]]; then
        fail "the receipt carries no digest of the client artifact the consumer ran with"
    fi
    if [[ -z "$RESOLVED_VERSION" ]]; then
        fail "the receipt carries no version the consumer resolved; it would say nothing measured"
    fi
    {
        printf 'consumer=1\n'
        printf 'sdk_version=%s\n' "$RESOLVED_VERSION"
        printf 'sdk_requested_version=%s\n' "$VERSION"
        printf 'sdk_repository_versions=%s\n' "$REPOSITORY_VERSIONS"
        printf 'sdk_repository=%s\n' "$REPO"
        printf 'run_id=%s\n' "$RUN_ID"
        printf 'question=answered\n'
        printf 'artifacts_matched=%s\n' "$MATCHED"
        printf 'client_digest=%s\n' "$CLIENT_DIGEST"
        printf 'negative_empty_repository=refused\n'
        printf 'negative_withdrawn_dependency=refused\n'
    } > "$OUT_FILE"
fi

printf 'CONSUMER: PASS\n'
