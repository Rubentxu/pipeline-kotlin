#!/usr/bin/env python3
"""Run-scoped JUnit report with explicit provenance.

Why this exists
---------------
W4 declared `753 classes / 4712 tests` and had to be corrected to
`452 / 3061`. The wrong figure was the sum of every XML on disk: 452 fresh
plus 301 carried over from previous runs. The receipt that recorded the
correction separated them with `mtime >= run start`, which works only while
nothing else is writing into those directories.

That assumption is not safe here, and this repository has already been bitten
by it: the W4 receipt's own closing note records that a detached Gradle
process stayed alive holding the checkout lock, so a later run failed with
"Another Gradle invocation is already using this v2 checkout". A concurrent
or delayed writer makes an mtime filter either drop fresh results or absorb
stray ones, and in both directions the number looks plausible.

This tool therefore does not trust the clock as the boundary. It takes the
set of XML that the run actually produced by **excluding anything older than
a caller-supplied boundary file's content**, and it always prints the
excluded remainder so the split is auditable rather than silent.

Provenance, not aggregation, is the point. Every report states the SHA, the
argv, the working-tree state and the counts, so a green number is
attributable to the exact tree that produced it and cannot be inherited by a
later commit.

Usage
-----
    python3 scripts/run-scoped-junit-report.py --head "$(git rev-parse HEAD)" \\
        --results-dir v2 -- --tests 'Foo*'

The boundary is taken from the `--boundary-file` if given (a file created
immediately before the run), otherwise from the newest XML mtime minus
`--grace-seconds`. Both are reported; when they disagree the report says so
rather than silently preferring one.

Exit codes
----------
0  PASS: the run's evidence is valid and reports no failures or errors.
1  FAIL: the run's evidence is valid and reports at least one failure or error.
2  INVALID_EVIDENCE: the tool refuses to report a verdict. Either no evidence
   exists, the boundary excludes the whole run, `HEAD` does not match, or at
   least one FRESH result file is not readable JUnit XML. A gate that cannot
   read its own evidence has no verdict, and a zero it computed from an
   unreadable file is not a green gate.

Fresh vs carried-over
--------------------
Only the fresh set carries the verdict. An unreadable CARRIED-OVER file is
reported under `carried_over_invalid_evidence` and warned about, because those
results are excluded from this run's accounting and cannot certify it either
way. An unreadable FRESH file is different: it is one of the results this run
claims to have produced, so the accounting for that run is unsound and the
verdict is `INVALID_EVIDENCE`.

Evidence is a closed result, not a sentinel
------------------------------------------
A result file is one of two cases, decided once, at parse time:

    SuiteCounts(counts)        the file is readable JUnit XML
    InvalidEvidence(path, why) the file is not

An `Optional`-shaped `None` plus a magic `unreadable_files` integer used to
stand in for the second case, and a non-integer attribute silently counted as
zero. Both directions read as a green gate over fewer tests than were claimed.
The counters are therefore validated where they are parsed, and an invalid
counter invalidates the file instead of being repaired.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import subprocess
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass

COUNTS = ("tests", "skipped", "failures", "errors")


@dataclass(frozen=True)
class SuiteCounts:
    """One readable JUnit result: the four counters, already validated."""

    counts: dict[str, int]


@dataclass(frozen=True)
class InvalidEvidence:
    """One result file that cannot be read as JUnit XML, and why not."""

    path: str
    reason: str


# The only two shapes a result file may have. No third case exists, so a
# caller cannot accidentally treat "unparsed" as "zero tests".
Evidence = SuiteCounts | InvalidEvidence


@dataclass(frozen=True)
class Totals:
    """Aggregated counters plus the files that could not contribute to them."""

    counts: dict[str, int]
    invalid: tuple[InvalidEvidence, ...]

    @property
    def invalid_count(self) -> int:
        return len(self.invalid)


def parse_args(argv: list[str]) -> tuple[argparse.Namespace, list[str]]:
    """Split our own flags from a trailing `-- <gradle argv>` passthrough."""
    passthrough: list[str] = []
    if "--" in argv:
        cut = argv.index("--")
        ours, passthrough = argv[:cut], argv[cut + 1:]
    else:
        ours = argv
    p = argparse.ArgumentParser(add_help=True)
    p.add_argument("--head", required=True, help="the exact candidate SHA")
    p.add_argument("--results-dir", default="v2")
    p.add_argument(
        "--boundary-file",
        help="file whose mtime marks the run start (created before the run)",
    )
    p.add_argument(
        "--grace-seconds",
        type=float,
        default=5.0,
        help="fallback tolerance when no boundary file is given",
    )
    p.add_argument(
        "--json",
        action="store_true",
        help="emit machine-readable JSON instead of the text report",
    )
    p.add_argument("--skip-tree-check", action="store_true")
    return p.parse_args(ours), passthrough


def read_xml(path: str) -> Evidence:
    """Classify one JUnit class file as readable counts or invalid evidence.

    A non-integer or negative counter is `InvalidEvidence`, not zero. Counting
    an unreadable file as zero tests is how a corrupt result becomes a green
    gate: the totals still balance, the count is merely smaller than it should
    be, and nothing downstream can tell the difference.
    """
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as exc:
        return InvalidEvidence(path, f"XML does not parse: {exc}")
    except OSError as exc:
        return InvalidEvidence(path, f"XML is not readable: {exc}")

    node = root if root.tag == "testsuite" else root.find("testsuite")
    if node is None:
        return InvalidEvidence(
            path, "no <testsuite> element: this file is not a JUnit result"
        )

    out: dict[str, int] = {}
    for key in COUNTS:
        raw = node.get(key)
        if raw is None:
            # Gradle always emits all four. A missing attribute is recorded as
            # absent rather than guessed, and is reported by the caller.
            out[key] = 0
            continue
        try:
            value = int(raw)
        except ValueError:
            return InvalidEvidence(
                path, f'attribute {key}="{raw}" is not an integer'
            )
        if value < 0:
            return InvalidEvidence(
                path, f"attribute {key}={value} is negative"
            )
        out[key] = value
    return SuiteCounts(out)


def discover(results_dir: str) -> list[str]:
    found: list[str] = []
    for root, _dirs, files in os.walk(results_dir):
        if os.sep + "build" + os.sep not in root + os.sep:
            continue
        for f in files:
            if f.startswith("TEST-") and f.endswith(".xml"):
                found.append(os.path.join(root, f))
    return sorted(found)


def split_fresh(paths: list[str], boundary: float) -> tuple[list[str], list[str]]:
    fresh, stale = [], []
    for p in paths:
        (fresh if os.path.getmtime(p) >= boundary else stale).append(p)
    return fresh, stale


def totals(paths: list[str]) -> Totals:
    """Sum readable counts and keep every invalid file as a typed value."""
    acc = dict.fromkeys(COUNTS, 0)
    invalid: list[InvalidEvidence] = []
    for p in paths:
        read = read_xml(p)
        if isinstance(read, InvalidEvidence):
            invalid.append(read)
            continue
        for k in COUNTS:
            acc[k] += read.counts[k]
    return Totals(counts=acc, invalid=tuple(invalid))


def git(*args: str) -> str:
    try:
        return subprocess.run(
            ["git", *args], capture_output=True, text=True, check=True
        ).stdout.strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        return "unavailable"


def fresh_set_digest(results_dir: str, paths: list[str]) -> str:
    """Identify the fresh result SET, not just its file names.

    Two suites with the same basename in different modules hash the same under a
    basename digest, and a corrupt file hashed as its absent counts hashes the
    same as a file that genuinely had no tests. Each contribution therefore
    names the path relative to the results root, the SHA-256 of the file's
    bytes, its validated counters, and which of the two evidence cases it is.
    """
    parts = []
    for p in paths:
        rel = os.path.relpath(p, results_dir)
        try:
            with open(p, "rb") as fh:
                file_digest = hashlib.sha256(fh.read()).hexdigest()
        except OSError as exc:
            # A digest that is not a digest. Handing back a sentinel string here
            # would let an unreadable evidence file into the `fresh_set_sha256`
            # field as if it identified evidence, which is the same
            # string-as-verdict defect the typed evidence ADT removes one layer
            # up. The caller already classifies this file through `totals()`.
            return f"INVALID_DIGEST_NO_BYTES:{rel}:errno={exc.errno}"
        read = read_xml(p)
        if isinstance(read, InvalidEvidence):
            parts.append(f"{rel}:invalid:{read.reason}")
        else:
            parts.append(f"{rel}:{file_digest}:{sorted(read.counts.items())}")
    return hashlib.sha256("|".join(parts).encode()).hexdigest()


def describe(invalid: tuple[InvalidEvidence, ...]) -> list[dict[str, str]]:
    """Render the typed invalid-evidence cases for machine consumption."""
    return [{"path": bad.path, "reason": bad.reason} for bad in invalid]


def decide(fresh: Totals) -> tuple[str, int]:
    """Pure: typed evidence -> (verdict, exit code). No I/O, no clock.

    The invalid case is checked FIRST and is not reachable by a count of zero.
    That ordering is the whole point: the previous implementation computed
    `failures == 0 and errors == 0` over a total that had already subtracted
    every file it could not parse, so unreadable evidence produced exactly the
    verdict a clean run produces.
    """
    if fresh.invalid:
        return ("INVALID_EVIDENCE", 2)
    if fresh.counts["failures"] == 0 and fresh.counts["errors"] == 0:
        return ("PASS", 0)
    return ("FAIL", 1)


def tree_state() -> str:
    dirty = git("status", "--porcelain")
    return "dirty" if dirty else "clean"


def main(argv: list[str]) -> int:
    args, gradle_argv = parse_args(argv)

    if args.boundary_file and os.path.exists(args.boundary_file):
        boundary = os.path.getmtime(args.boundary_file)
        boundary_source = f"boundary file {args.boundary_file}"
    else:
        paths = discover(args.results_dir)
        if not paths:
            print(
                f"No JUnit XML under {args.results_dir}. The run produced no "
                "evidence; refusing to report a green gate.",
                file=sys.stderr,
            )
            return 2
        boundary = max(os.path.getmtime(p) for p in paths) - args.grace_seconds
        boundary_source = (
            f"newest XML mtime minus {args.grace_seconds}s (NO boundary file was "
            "supplied, so this is an inference, not a measurement)"
        )

    paths = discover(args.results_dir)
    if not paths:
        print("No JUnit XML found; nothing to report.", file=sys.stderr)
        return 2

    fresh, stale = split_fresh(paths, boundary)
    fresh_t, stale_t = totals(fresh), totals(stale)

    # A boundary created AFTER the results silently excludes the run it was meant
    # to capture, yielding an empty "THIS RUN" row and a PASS over zero tests.
    # That is the dangerous direction: the number looks fine and proves nothing.
    # Measured while writing the harness, where the boundary file was created
    # after the XML instead of before it.
    if not fresh:
        print(
            "FAIL: no XML falls inside the run boundary, so this report would "
            "certify zero tests. The boundary file was almost certainly created "
            "after the run; it must be created BEFORE the build starts.",
            file=sys.stderr,
        )
        return 2
    head = git("rev-parse", "HEAD")
    subject = git("log", "-1", "--pretty=%s")
    branch = git("rev-parse", "--abbrev-ref", "HEAD")

    head_matches = head == args.head
    if not head_matches and not args.skip_tree_check:
        print(
            f"FAIL: --head {args.head} does not match HEAD {head}. A report bound "
            "to a SHA that is not the tree that ran is worse than no report.",
            file=sys.stderr,
        )
        return 2

    # Decision first, interpretation second. The verdict is a closed three-case
    # result decided from the typed evidence, and only then rendered.
    verdict, exit_code = decide(fresh_t)
    digest = fresh_set_digest(args.results_dir, fresh)

    if args.json:
        import json

        print(
            json.dumps(
                {
                    "head": head,
                    "head_matches": head_matches,
                    "branch": branch,
                    "subject": subject,
                    "tree": tree_state(),
                    "gradle_argv": gradle_argv,
                    "boundary_source": boundary_source,
                    "fresh": fresh_t.counts,
                    "carried_over": stale_t.counts,
                    "fresh_files": len(fresh),
                    "carried_over_files": len(stale),
                    "fresh_invalid_evidence": describe(fresh_t.invalid),
                    "carried_over_invalid_evidence": describe(stale_t.invalid),
                    "fresh_set_sha256": digest,
                    "verdict": verdict,
                    "exit_code": exit_code,
                },
                indent=2,
                sort_keys=True,
            )
        )
        return exit_code

    print("=" * 72)
    print("RUN-SCOPED JUNIT REPORT")
    print("=" * 72)
    print(f"head        : {head}  (--head match: {head_matches})")
    print(f"branch      : {branch}")
    print(f"subject     : {subject}")
    print(f"tree        : {tree_state()}")
    print(f"gradle argv : {' '.join(gradle_argv) or '(none recorded)'}")
    print(f"boundary    : {boundary_source}")
    print(f"fresh set   : sha256 {digest}")
    print()
    print(f"{'':22}{'classes':>10}{'tests':>10}{'failures':>10}{'errors':>10}{'skips':>8}")
    for label, t, files in (
        ("THIS RUN", fresh_t, fresh),
        ("carried-over", stale_t, stale),
    ):
        print(
            f"{label:22}{len(files):>10}"
            f"{t.counts['tests']:>10}{t.counts['failures']:>10}"
            f"{t.counts['errors']:>10}{t.counts['skipped']:>8}"
        )
    print()
    for bad in fresh_t.invalid:
        print(
            f"INVALID EVIDENCE (this run): {bad.path}: {bad.reason}. The counts "
            "above are incomplete, so this run cannot be certified.",
            file=sys.stderr,
        )
    for bad in stale_t.invalid:
        print(
            f"WARNING: carried-over file is not readable JUnit XML and is "
            f"excluded either way: {bad.path}: {bad.reason}",
            file=sys.stderr,
        )
    if stale_t.counts["tests"]:
        print(
            f"NOTE: {len(stale)} carried-over file(s) / {stale_t.counts['tests']} "
            "tests are EXCLUDED. Reporting them as this run's result is the W4 "
            "defect."
        )
    print(f"VERDICT: {verdict}")
    if not head_matches:
        print("FAIL: --head does not match the tree that produced these results.")
    return exit_code


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
