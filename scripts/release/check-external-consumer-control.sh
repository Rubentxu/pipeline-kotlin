#!/usr/bin/env bash
# check-external-consumer-control.sh — prove that the isolated-consumer gate
# can REFUSE.
#
# WHAT IT DOES. It copies the repository the consumer resolves from, breaks
# the copy in the ways a release can break it, and runs the gate and the
# consumer's own law against the broken copies. They have to REFUSE, and
# each refusal has to NAME the versions it is about.
#
# WHY IT EXISTS. A gate that has never refused anything proves nothing: the
# consumer it accepted could be accepted just as well by a gate that accepts
# everything. The gate pays two negatives of its own —an empty repository and
# a withdrawn dependency— and this control pays the ones that are about the
# IDENTITY of the SDK the consumer resolved.
#
# THE THREE BREAKAGES, and why they are three:
#
#   1. THE REPOSITORY THAT DOES NOT CARRY THE RELEASE. The release names the
#      version the consumer resolves, and a repository that does not carry it
#      cannot answer the release's question. The gate has to refuse it and
#      name the version that is missing, instead of measuring another one.
#
#   2. THE RESOLUTION THAT DEVIATES. The resolution document of the release
#      pins the contracts at another version —a stale version in the
#      document that decides the resolution— and the consumer ends up running
#      the bytes of a publication this release does not publish. The law that
#      compares the version the consumer RESOLVED with the version it ASKED
#      FOR lives in the consumer's own build, because that is where the
#      resolution happens, and the control runs that build against the broken
#      copy. The resolution itself SUCCEEDS there: the bytes are the ones the
#      repository declares, and only the version is wrong, so a refusal that
#      named the version is the version law and not a broken resolution.
#
#   3. THE SAME BROKEN COPY, THROUGH THE GATE. The gate is handed the
#      repository whose resolution document pins another version, and it has
#      to refuse it: a gate that resolved the contracts of another
#      publication and published the release would certify a consumer running
#      with an SDK this release does not publish.
#
# WHY THE COPY AND NOT THE REPOSITORY. The repository the publication lands
# in is never touched: the COPY is what is broken, and it lives in a
# temporary directory that is removed when this control ends. It publishes
# nothing and it signs nothing.
#
# Usage:
#   check-external-consumer-control.sh --expect-version <v> [--sdk-repo <dir>]
#       [--gate <file>]
#
# Exit codes: 0 = the gate and the consumer refused every broken repository,
# naming the versions; 1 = the control failed (one of them accepted the
# breakage, or refused it blind); 2 = usage error, or the instrument could
# not run.

set -Eeuo pipefail

SDK_REPO=""
EXPECT_VERSION=""
GATE=""

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
        --gate) GATE="${2:-}"; shift 2 ;;
        -h|--help) usage ;;
        *)
            printf 'check-external-consumer-control: unknown argument %s\n' "$1" >&2
            usage
            ;;
    esac
done

[[ -n "$EXPECT_VERSION" ]] || {
    printf 'check-external-consumer-control: --expect-version is required: the release names the SDK the consumer resolves\n' >&2
    exit 2
}

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
[[ -n "$GATE" ]] || GATE="$repo_root/scripts/release/verify-external-consumer.sh"
[[ -f "$GATE" ]] || {
    printf 'check-external-consumer-control: there is no gate at %s\n' "$GATE" >&2
    exit 2
}

WORK="$(mktemp -d "${TMPDIR:-/tmp}/pipelinek-consumer-control.XXXXXX")"
cleanup() {
    case "$WORK" in
        /tmp/*|/var/tmp/*|"${TMPDIR:-/tmp}"/*) rm -rf "$WORK" ;;
        *) printf 'check-external-consumer-control: refusing to delete %s\n' "$WORK" >&2 ;;
    esac
}
trap cleanup EXIT

# The repository the control breaks. Without one, the SDK of this revision is
# published into the build tree, the way the gate publishes it: the control
# has to run against a real publication, and a publication it makes itself is
# as good as one it finds.
if [[ -n "$SDK_REPO" ]]; then
    if [[ ! -d "$SDK_REPO" ]]; then
        printf 'check-external-consumer-control: %s is not a directory\n' "$SDK_REPO" >&2
        exit 2
    fi
    SOURCE_REPO="$(cd -- "$SDK_REPO" && pwd)"
else
    if ! "$repo_root/pipelinek/gradlew" --console=plain -p "$repo_root/pipelinek" \
        :publishSdkForExternalPlugin > "$WORK/publish.log" 2>&1; then
        tail -30 "$WORK/publish.log" >&2
        printf 'check-external-consumer-control: the SDK of this revision could not be published\n' >&2
        exit 2
    fi
    SOURCE_REPO="$repo_root/pipelinek/build/sdk-repo"
fi

# The version the control publishes the contracts under, besides the one under
# test: the version the broken resolution document points at.
OTHER_VERSION="${EXPECT_VERSION}.1"

# ----------------------------------------------------------------------------
# 1. The repository that does not carry the release's version.
# ----------------------------------------------------------------------------

MISSING_VERSION="0.0.0-not-published"

if bash "$GATE" --sdk-repo "$SOURCE_REPO" --expect-version "$MISSING_VERSION" \
    --out "$WORK/verdict-missing.txt" > "$WORK/missing.log" 2>&1; then
    printf 'check-external-consumer-control: the gate ACCEPTED a repository that carries no version %s\n' \
        "$MISSING_VERSION" >&2
    exit 1
fi

if ! grep -q -- "$MISSING_VERSION" "$WORK/missing.log"; then
    printf 'check-external-consumer-control: the gate refused the repository without naming the version %s it does not carry\n' \
        "$MISSING_VERSION" >&2
    sed 's/^/    /' "$WORK/missing.log" >&2
    exit 1
fi

printf 'negative control: the gate refused a repository that carries no version %s, naming it\n' \
    "$MISSING_VERSION"

# ----------------------------------------------------------------------------
# 2. The copy whose resolution document pins another version.
# ----------------------------------------------------------------------------

BROKEN_REPO="$WORK/broken-repository"

if ! python3 -I - "$SOURCE_REPO" "$BROKEN_REPO" "$EXPECT_VERSION" "$OTHER_VERSION" <<'PY'
"""Build a copy of a published SDK repository whose resolution document is wrong.

The copy carries the version under test and the other version, and nothing
else: the publication it comes from may carry the publications of other
releases, and the control has to measure what it says it measures on any
machine.

The contracts of the version under test are published AGAIN under the other
version, with the resolution document of the version under test pointing at
that other version.

The bytes are the same bytes: the copy declares the digest of what it
carries, so the digest law is satisfied and only the version is wrong. That
is what makes the refusal that follows the VERSION law and not a law about
bytes.
"""
import hashlib
import json
import pathlib
import re
import shutil
import sys

source = pathlib.Path(sys.argv[1])
target = pathlib.Path(sys.argv[2])
version = sys.argv[3]
other = sys.argv[4]

group_paths = ("dev/rubentxu/pipelinek", "dev/rubentxu/pipeline/v2")


def write_sidecars(path):
    """Write the digests of the file beside it, the way the publication does."""
    data = path.read_bytes()
    for suffix, digest in (
        (".md5", hashlib.md5(data).hexdigest()),
        (".sha1", hashlib.sha1(data).hexdigest()),
        (".sha256", hashlib.sha256(data).hexdigest()),
        (".sha512", hashlib.sha512(data).hexdigest()),
    ):
        path.with_name(path.name + suffix).write_text(digest, encoding="utf-8")


def copy_version_under_test(source_directory, target_directory, name):
    """Copy the artifacts of the SDK at one version, and no other version."""
    for group_path in group_paths:
        group_source = source_directory / group_path
        if not group_source.is_dir():
            continue
        for artifact_directory in sorted(group_source.iterdir()):
            if not artifact_directory.is_dir():
                continue
            published = artifact_directory / name
            if not published.is_dir():
                continue
            destination = target_directory / group_path / artifact_directory.name
            destination.mkdir(parents=True, exist_ok=True)
            shutil.copytree(published, destination / name)


copy_version_under_test(source, target, version)

# The contracts of the version under test, published again under the other
# version: the same bytes, under another name of a publication.
for group_path in group_paths:
    group_target = target / group_path
    if not group_target.is_dir():
        continue
    for artifact_directory in sorted(group_target.iterdir()):
        if not artifact_directory.is_dir():
            continue
        published = artifact_directory / version
        if not published.is_dir():
            continue
        other_directory = artifact_directory / other
        other_directory.mkdir(parents=True, exist_ok=True)
        for published_file in sorted(published.iterdir()):
            name = published_file.name.replace(f"-{version}", f"-{other}")
            destination = other_directory / name
            if published_file.suffix in (".pom", ".module"):
                destination.write_text(
                    published_file.read_text(encoding="utf-8").replace(version, other),
                    encoding="utf-8",
                )
                write_sidecars(destination)
            else:
                shutil.copyfile(published_file, destination)

# The resolution document of the version under test, pointing at the other
# version. Its own version does not move: the consumer asks for it by the
# name of the release, and the document it gets is the one of that release.
resolution_document = None
for group_path in group_paths:
    candidate = target / group_path / "pipelinek-sdk-bom" / version
    if candidate.is_dir():
        resolution_document = candidate
        break
if resolution_document is None:
    raise SystemExit(f"the copy carries no resolution document of version {version}")

for document in sorted(resolution_document.iterdir()):
    if document.suffix == ".module":
        parsed = json.loads(document.read_text(encoding="utf-8"))
        for variant in parsed.get("variants", []):
            for constraint in variant.get("dependencyConstraints", []):
                constraint["version"]["requires"] = other
        document.write_text(json.dumps(parsed, indent=1) + "\n", encoding="utf-8")
        write_sidecars(document)
    elif document.suffix == ".pom":
        text = document.read_text(encoding="utf-8")
        managed = re.search(r"<dependencyManagement>.*?</dependencyManagement>", text, re.S)
        if managed is None:
            raise SystemExit("the resolution document declares no dependency management")
        block = managed.group(0).replace(
            f"<version>{version}</version>", f"<version>{other}</version>"
        )
        document.write_text(
            text[: managed.start()] + block + text[managed.end() :], encoding="utf-8"
        )
        write_sidecars(document)
PY
then
    printf 'check-external-consumer-control: the copy of the repository could not be broken\n' >&2
    exit 2
fi

# The consumer, in an isolated copy of its sources, exactly as the gate builds
# it. The toolchain declaration travels with the copy because it belongs to
# the machine and not to the sources.
CONSUMER="$WORK/consumer"
mkdir -p "$CONSUMER"
cp -r "$repo_root/examples/example-service-client/src" "$CONSUMER/"
cp "$repo_root/examples/example-service-client/build.gradle.kts" \
    "$repo_root/examples/example-service-client/settings.gradle.kts" "$CONSUMER/"
cp "$repo_root/.tool-versions" "$CONSUMER/"

if "$repo_root/pipelinek/gradlew" --console=plain -p "$CONSUMER" \
    -PsdkRepo="$BROKEN_REPO" -PsdkVersion="$EXPECT_VERSION" --refresh-dependencies \
    verifyResolvedIdentity > "$WORK/deviating.log" 2>&1; then
    printf 'check-external-consumer-control: the consumer ACCEPTED a resolution at version %s while it asked for %s\n' \
        "$OTHER_VERSION" "$EXPECT_VERSION" >&2
    exit 1
fi

# The refusal has to be the VERSION law: a resolution that failed for any
# other reason would leave the law unmeasured.
if ! grep -q -- "the version it asked for is $EXPECT_VERSION" "$WORK/deviating.log"; then
    printf 'check-external-consumer-control: the consumer refused the deviating resolution for another reason than the version it asked for\n' >&2
    sed 's/^/    /' "$WORK/deviating.log" >&2
    exit 1
fi

if ! grep -q -- "resolved version $OTHER_VERSION" "$WORK/deviating.log"; then
    printf 'check-external-consumer-control: the consumer refused the deviating resolution without naming the version %s it resolved\n' \
        "$OTHER_VERSION" >&2
    sed 's/^/    /' "$WORK/deviating.log" >&2
    exit 1
fi

printf 'negative control: the consumer refused a resolution at version %s while it asked for %s, naming both\n' \
    "$OTHER_VERSION" "$EXPECT_VERSION"

# ----------------------------------------------------------------------------
# 3. The same broken copy, through the gate.
# ----------------------------------------------------------------------------

if bash "$GATE" --sdk-repo "$BROKEN_REPO" --expect-version "$EXPECT_VERSION" \
    --out "$WORK/verdict-broken.txt" > "$WORK/broken.log" 2>&1; then
    printf 'check-external-consumer-control: the gate ACCEPTED a repository whose resolution document pins version %s\n' \
        "$OTHER_VERSION" >&2
    exit 1
fi

printf 'negative control: the gate refused the repository whose resolution document pins version %s\n' \
    "$OTHER_VERSION"
