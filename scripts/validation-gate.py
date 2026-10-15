#!/usr/bin/env python3
"""Run the harness validation gate from OUTSIDE Gradle.

## Why this is a script and not a Gradle task

`v2/settings.gradle.kts` takes an exclusive file lock on the checkout for the duration of a build,
and refuses a second concurrent invocation. A `JavaExec` task that shells out to `gradlew` to run
the selected suites would therefore always find the lock held — by itself — and every suite would
fail with "Another Gradle invocation is already using this v2 checkout". That is not a
misconfiguration: the gate genuinely needs to start builds after the build that launched it has
finished.

So the gate is a separate process:

    1. build the harness jar          (Gradle, one invocation, lock taken and released)
    2. run the gate main class        (plain JVM, no lock held)
    3. the gate runs the suites       (Gradle, one invocation per module, sequentially)

Step 3 is sequential on purpose. Two suites in different modules could in principle run
concurrently, but they would contend for the same lock, and a gate that has to serialise its own
workarounds is not simpler than one that runs them in order.

## Usage

    scripts/validation-gate.py --base origin/main --commit <sha> [--observed-commit <sha>]

`--observed-commit` is what the INSTALLED binary reports. When it is omitted the gate treats the
observed commit as unknown, and unknown refuses. It is never inferred from Git: the point of the
check is to notice when the binary on disk is not the one the commit describes, and asking Git
would make the check agree with itself.
"""

from __future__ import annotations

import argparse
import os
import pathlib
import subprocess
import sys

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
V2 = REPO_ROOT / "v2"
GRADLEW = V2 / "gradlew"
HARNESS_LIBS = V2 / "test-harness" / "build" / "libs"
JAR_PREFIX = "test-harness-"
MAIN_CLASS = "dev.rubentxu.pipeline.v2.harness.plan.GateCliKt"


def harness_jar() -> pathlib.Path | None:
    """The harness jar for this build.

    Resolved by pattern rather than by a fixed name because the jar carries the project version
    (`test-harness-0.61.0.jar`), and a script that pinned the name would break on every release and
    would be silently wrong on a checkout carrying two versions in `libs/`.

    When several match, the refusal is deliberate: picking the newest would let a stale jar answer
    for the tree under test, which is the same class of defect as reading a previous run's evidence.
    """
    candidates = sorted(HARNESS_LIBS.glob(f"{JAR_PREFIX}*.jar"))
    if len(candidates) == 1:
        return candidates[0]
    if not candidates:
        return None
    raise SystemExit(
        f"more than one harness jar in {HARNESS_LIBS}: "
        f"{[c.name for c in candidates]}. Remove the stale ones so the gate runs what this tree built."
    )


def run(argv: list[str], cwd: pathlib.Path) -> int:
    print(f"\n$ {' '.join(argv)}", flush=True)
    completed = subprocess.run(argv, cwd=str(cwd))
    return completed.returncode


def build_harness() -> int:
    """Build the harness jar. The only Gradle invocation the gate itself needs.

    `clean` first so `libs/` holds exactly one jar: the gate refuses on ambiguity rather than
    guessing which of two versions answers for the tree, and a leftover from an older build is
    ambiguity the gate did not create.
    """
    return run([str(GRADLEW), "--quiet", ":test-harness:clean", ":test-harness:jar"], V2)


def runtime_classpath() -> str:
    """The harness jar plus the Kotlin stdlib it needs at runtime.

    A bare `java -cp harness.jar` fails with `NoClassDefFoundError: kotlin/jvm/internal/Intrinsics`:
    the jar carries the harness classes, not the Kotlin runtime that every one of them calls. The
    stdlib is resolved out of the Gradle cache rather than pinned to a version here, so the classpath
    tracks whatever the build just resolved instead of going stale on the next Kotlin bump.

    It is resolved AFTER `:test-harness:jar` has run, because resolving the Kotlin coordinates needs
    the Gradle cache that the build populates.
    """
    jar = harness_jar()
    if jar is None:
        raise SystemExit(f"no harness jar in {HARNESS_LIBS} after a successful build")

    # Ask Gradle to print the runtime classpath rather than guessing at cache layout: the cache path
    # includes a hash of the module metadata, so a hand-built glob would be right until it is not.
    classpath_file = HARNESS_LIBS / "runtime-classpath.txt"
    run(
        [
            str(GRADLEW),
            "--quiet",
            "-q",
            ":test-harness:printRuntimeClasspath",
            f"-Poutput={classpath_file}",
        ],
        V2,
    )
    if not classpath_file.exists():
        raise SystemExit(f"Gradle did not write {classpath_file}; refusing to guess a classpath")
    entries = [line for line in classpath_file.read_text().splitlines() if line.strip()]
    if not entries:
        raise SystemExit(f"{classpath_file} is empty; refusing to run with an empty classpath")
    return os.pathsep.join(entries)


def run_gate(base: str, commit: str, observed: str | None) -> int:
    """Run the gate main class with a plain JVM, holding no Gradle lock."""
    classpath = runtime_classpath()
    # An empty third argument is not "unknown": it is an empty string, which does not equal the
    # commit and therefore refuses. The distinction matters, so the flag is passed as a marker and
    # translated here rather than being assembled by the caller.
    argv = [
        "java",
        "-cp",
        classpath,
        MAIN_CLASS,
        base,
        commit,
        observed if observed is not None else "",
    ]
    print(f"\n$ {' '.join(argv)}", flush=True)
    completed = subprocess.run(argv, cwd=str(REPO_ROOT))
    return completed.returncode


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base", default="origin/main", help="ref the plan is computed against")
    parser.add_argument("--commit", required=True, help="commit the evidence must be bound to")
    parser.add_argument(
        "--observed-commit",
        default=None,
        help="commit the installed binary reports; omitted means unknown, and unknown refuses",
    )
    args = parser.parse_args()

    if not GRADLEW.exists():
        print(f"missing wrapper: {GRADLEW}", file=sys.stderr)
        return 1

    # The dirty-tree refusal belongs to the gate itself, not here, so that running the gate
    # directly and running it through this script refuse identically. This script only refuses
    # what would make the gate's own answer meaningless: a harness it could not build.
    if build_harness() != 0:
        print("could not build the harness; refusing", file=sys.stderr)
        return 2

    code = run_gate(args.base, args.commit, args.observed_commit)
    if code == 0:
        print("\nVALIDATION GATE: discharged", flush=True)
    else:
        print(f"\nVALIDATION GATE: refused (exit {code})", flush=True)
    return code


if __name__ == "__main__":
    sys.exit(main())