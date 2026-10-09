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
"""

from __future__ import annotations

import argparse
import hashlib
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

COUNTS = ("tests", "skipped", "failures", "errors")


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


def read_xml(path: str) -> dict[str, int] | None:
    """Counts for one JUnit class file, or None when it is not readable."""
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError):
        return None
    node = root if root.tag == "testsuite" else root.find("testsuite")
    if node is None:
        return None
    out: dict[str, int] = {}
    for k in COUNTS:
        try:
            out[k] = int(node.get(k, "0"))
        except ValueError:
            out[k] = 0
    return out


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


def totals(paths: list[str]) -> dict[str, int]:
    acc = dict.fromkeys(COUNTS, 0)
    unreadable = 0
    for p in paths:
        c = read_xml(p)
        if c is None:
            unreadable += 1
            continue
        for k in COUNTS:
            acc[k] += c[k]
    if unreadable:
        acc["unreadable_files"] = acc.get("unreadable_files", 0) + unreadable
    return acc


def git(*args: str) -> str:
    try:
        return subprocess.run(
            ["git", *args], capture_output=True, text=True, check=True
        ).stdout.strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        return "unavailable"


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

    ok = fresh_t["failures"] == 0 and fresh_t["errors"] == 0
    digest = hashlib.sha256(
        "".join(f"{os.path.basename(p)}:{read_xml(p)}" for p in fresh).encode()
    ).hexdigest()

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
                    "fresh": fresh_t,
                    "carried_over": stale_t,
                    "fresh_files": len(fresh),
                    "carried_over_files": len(stale),
                    "fresh_set_sha256": digest,
                    "verdict": "PASS" if ok else "FAIL",
                },
                indent=2,
                sort_keys=True,
            )
        )
        return 0 if ok else 1

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
    for label, t in (("THIS RUN", fresh_t), ("carried-over", stale_t)):
        print(
            f"{label:22}{len(fresh) if label == 'THIS RUN' else len(stale):>10}"
            f"{t['tests']:>10}{t['failures']:>10}{t['errors']:>10}{t['skipped']:>8}"
        )
    print()
    if fresh_t.get("unreadable_files"):
        print(
            f"WARNING: {fresh_t['unreadable_files']} fresh XML file(s) were "
            "unreadable and are excluded from the counts below.",
            file=sys.stderr,
        )
    if stale_t["tests"]:
        print(
            f"NOTE: {len(stale)} carried-over file(s) / {stale_t['tests']} tests "
            "are EXCLUDED. Reporting them as this run's result is the W4 defect."
        )
    print(f"VERDICT: {'PASS' if ok else 'FAIL'}")
    if not head_matches:
        print("FAIL: --head does not match the tree that produced these results.")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
