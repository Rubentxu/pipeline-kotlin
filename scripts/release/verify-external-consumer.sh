#!/usr/bin/env bash
# verify-external-consumer.sh — the isolated-consumer gate.
#
# WHAT IT ANSWERS, IN ONE SENTENCE
#
#   Can a program OUTSIDE this repository resolve the published SDK by its
#   coordinates, compile against it, and ask a running service a real question?
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
#   1  the SDK of THIS revision, published to a TEMPORARY Maven repository
#   2  the consumer, in an ISOLATED COPY of its sources, resolved by
#      coordinates: the copy has no access to this tree's projects, so a
#      consumer that needed one would not build at all
#   3  the classpath the consumer runs with: no server, no provider, no
#      Jenkins, and every upstream class the SDK names
#   4  the bytes the consumer resolved: the ones the repository declares
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
# Usage:
#   verify-external-consumer.sh [--sdk-repo <dir>] [--out <file>]
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
        --out)      OUT_FILE="${2:-}"; shift 2 ;;
        -h|--help)  usage ;;
        *)
            printf 'verify-external-consumer: unknown argument %s\n' "$1" >&2
            usage
            ;;
    esac
done

CONSUMER_SOURCES="$repo_root/examples/example-service-client"
if [[ ! -d "$CONSUMER_SOURCES/src" ]]; then
    printf 'verify-external-consumer: the consumer sources are missing at %s\n' \
        "$CONSUMER_SOURCES" >&2
    exit 2
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/pipelinek-consumer.XXXXXX")"
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
    case "$WORK" in
        /tmp/*|/var/tmp/*|"${TMPDIR:-/tmp}"/*) rm -rf "$WORK" ;;
        *) printf 'verify-external-consumer: refusing to delete %s\n' "$WORK" >&2 ;;
    esac
}
trap cleanup EXIT

fail() {
    printf 'verify-external-consumer: %s\n' "$1" >&2
    printf 'CONSUMER: FAIL\n'
    exit 1
}

row() {
    printf 'consumer: %s\n' "$1"
}

# ── 1. the SDK of this revision, in a temporary repository ──────────────────────
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

REPO="$WORK/sdk-repo"
cp -r "$SOURCE_REPO" "$REPO"

# The group the SDK is published under. The publication before the rename
# carried another one, and both are looked for: a reader that knew only the
# current one would answer "there is nothing here" about a repository of a
# previous release, and that answer would be a lie about the repository.
GROUP_PATH=""
for candidate in dev/rubentxu/pipelinek dev/rubentxu/pipeline/v2; do
    if [[ -d "$REPO/$candidate" ]]; then
        GROUP_PATH="$candidate"
        break
    fi
done
[[ -n "$GROUP_PATH" ]] || fail "the repository carries no SDK group at all"

# The version the consumer resolves. It is read from the repository and not
# asked for: a version somebody typed in is a version nobody measured.
VERSION="$(find "$REPO/$GROUP_PATH/pipelinek-service-client" -mindepth 1 -maxdepth 1 -type d \
    -printf '%f\n' 2>/dev/null | sort -u | head -1)"
[[ -n "$VERSION" ]] || fail "the repository holds no version of the SDK the consumer needs"
row "sdk repository       $REPO"
row "sdk version          $VERSION"

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
if ! consumer_build "$CONSUMER" "$REPO" --refresh-dependencies \
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
row "question              the service answered about run $RUN_ID"

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
row "negative              the same consumer failed against an empty repository, as it must"

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
row "negative              the same consumer failed with the client artifact withdrawn, as it must"

# ── the receipt ───────────────────────────────────────────────────────────────
if [[ -n "$OUT_FILE" ]]; then
    {
        printf 'consumer=1\n'
        printf 'sdk_version=%s\n' "$VERSION"
        printf 'sdk_repository=%s\n' "$REPO"
        printf 'run_id=%s\n' "$RUN_ID"
        printf 'question=answered\n'
        printf 'negative_empty_repository=refused\n'
        printf 'negative_withdrawn_dependency=refused\n'
    } > "$OUT_FILE"
fi

printf 'CONSUMER: PASS\n'
