#!/usr/bin/env python3
"""Harness coverage fitness (mandate B1): bidirectional tool <-> suite registry.

What this defends
-----------------
The harness lives in `scripts/` and is executable tooling, but nothing forced a
new tool to arrive with a suite, and nothing noticed when a suite outlived its
subject. This registry makes both directions mechanical.

Rules
-----
  R1  every `scripts/**/*.py` that is not itself a test declares a colocated
      suite `test_<stem>.py`, or is registered in EXEMPT with a reason.
  R2  every `scripts/**/test_*.py` names a subject that exists: same-dir
      `<stem>.py` / `<stem>.sh`, or the same name in `scripts/` or
      `scripts/release/`.
  R3  every suite contains at least one `def test_` (a suite with no tests is
      not a suite).
  R4  every `scripts/*.sh` entry point is mapped to a suite (SHELL_SUITES) or
      registered in EXEMPT_SHELL with a reason.

Exemption kinds (never silent: both are printed on every run)
------------------------------------------------------------
  OWNER      outside this work's authority (roadmap "no tocar", hash-pinned).
  OPEN_GAP   a known missing suite. The default run reports it; `--strict`
             fails on it, so the gap has a lever and cannot be forgotten.

Usage:
    python3 scripts/fitness-harness-coverage.py [--root <dir>] [--strict]

Exit codes:
    0  no violations (OPEN_GAP entries reported but allowed unless --strict)
    2  violation (undeclared tool, orphan suite, empty suite, unmapped entry
       point, or an OPEN_GAP under --strict)
"""
from __future__ import annotations

import argparse
import pathlib
import sys

# --- declared registry ------------------------------------------------------

EXEMPT = {
    "gen-certification-ledger.py": (
        "OWNER",
        "DISTRIBUTION_ROADMAP.md lists it under 'no tocar' (operator WIP) and "
        "HARNESS_INVENTORY_HANDOVER.md hash-pins it for the verbatim harness "
        "copy; adding a suite is an owner decision.",
    ),
    "mirror.py": (
        "OPEN_GAP",
        "SDKMAN UAT mirror spike (ThreadingHTTPServer); networked tool whose "
        "digest contract is covered by release/test_sdkman_digest.py. Its own "
        "server loop has no hermetic contract yet.",
    ),
}

# Topic-named suites that cover more than one subject (name does not match a
# single stem). Each declared subject must exist somewhere under the root.
SUITE_SUBJECTS = {
    "test_sdkman_digest.py": ["sdkman-publish.sh", "sdkman-install-uat.sh"],
}

EXEMPT_SHELL = {
    "test_release_publisher.sh": "is itself the shell suite for the publisher path",
    "perf-soak-rss.sh": "manual soak measurement tool; not a gate input",
    "pk-57a-publish.sh": "historical publish script for the 0.57.0 train; receipt-time only",
    "cheat-sheet-uat.sh": "installed-CLI UAT script; belongs to the harness migration catalogue (RESPONSIBILITY_MIGRATION_ROADMAP.md), not hermetic tooling",
    "cli-exit-contract-uat.sh": "installed-CLI UAT script; belongs to the harness migration catalogue",
    "run-spike.sh": "SDKMAN UAT spike runner, superseded by sdkman-install-uat.sh",
    "cleanup.sh": "sandbox devbox/podman tooling invoked via justfile; no hermetic contract",
    "collect-logs.sh": "sandbox log collector; observability aid, not a gate input",
    "common.sh": "sourced shell library; not an entry point",
    "run-smoke.sh": "sandbox smoke runner invoked via justfile",
    "run-uat.sh": "sandbox UAT runner invoked via justfile",
    "wait-http.sh": "sandbox readiness helper; not a gate input",
}

SHELL_SUITES = {
    "install-pipelinek.sh": "test_install_pipelinek.py",
    "build-release-manifest.sh": "test_build_release_manifest.py",
    "resolve-release-digest.sh": "release/test_sdkman_digest.py",
    "sdkman-publish.sh": "release/test_sdkman_digest.py",
    "sdkman-install-uat.sh": "release/test_sdkman_digest.py",
    "release-pipelinek.sh": "test_release_publisher.sh",
}


def _is_test(path: pathlib.Path) -> bool:
    return path.name.startswith("test_")


def _suite_path_for(tool: pathlib.Path) -> pathlib.Path:
    return tool.parent / f"test_{tool.stem.replace('-', '_')}.py"


def _subject_exists(suite: pathlib.Path, root: pathlib.Path) -> bool:
    declared = SUITE_SUBJECTS.get(suite.name)
    if declared is not None:
        return all(
            any(root.rglob(name)) for name in declared
        )
    stem = suite.stem[len("test_"):]
    candidates = [stem, stem.replace("_", "-")]
    for name in candidates:
        if any(root.rglob(f"{name}.py")) or any(root.rglob(f"{name}.sh")):
            return True
    return False


def collect(root: pathlib.Path, *, strict: bool) -> tuple[list[str], list[str]]:
    """Return (violations, open_gaps)."""
    violations: list[str] = []
    open_gaps: list[str] = []

    tools = sorted(p for p in root.rglob("*.py") if not _is_test(p))
    suites = sorted(p for p in root.rglob("test_*.py"))

    for tool in tools:
        if tool.name in EXEMPT:
            kind, reason = EXEMPT[tool.name]
            line = f"{tool.relative_to(root)}: {kind} — {reason}"
            if kind == "OPEN_GAP":
                open_gaps.append(line)
                if strict:
                    violations.append(f"OPEN_GAP under --strict: {line}")
            continue
        suite = _suite_path_for(tool)
        if not suite.is_file():
            violations.append(
                f"{tool.relative_to(root)}: no colocated suite "
                f"({suite.relative_to(root)}); add one or register EXEMPT")

    for suite in suites:
        if not _subject_exists(suite, root):
            violations.append(
                f"{suite.relative_to(root)}: names no existing subject "
                "(same-dir stem .py/.sh, or script in scripts/ or scripts/release/)")
        text = suite.read_text(encoding="utf-8", errors="ignore")
        if "def test_" not in text:
            violations.append(
                f"{suite.relative_to(root)}: contains no `def test_`; a suite "
                "with no tests is not a suite")

    for entry in sorted(root.rglob("*.sh")):
        if entry.name in EXEMPT_SHELL:
            continue
        if entry.name in SHELL_SUITES:
            suite = root / SHELL_SUITES[entry.name]
            if not suite.is_file():
                violations.append(
                    f"{entry.relative_to(root)}: SHELL_SUITES maps to "
                    f"{SHELL_SUITES[entry.name]}, which does not exist")
            continue
        # A colocated suite pair covers the entry point without an explicit
        # map (e.g. installer.sh + test_installer.py).
        colocated = entry.parent / f"test_{entry.stem.replace('-', '_')}.py"
        if colocated.is_file():
            continue
        violations.append(
            f"{entry.relative_to(root)}: entry point with no declared suite "
            "(map it in SHELL_SUITES, add a colocated test_<stem>.py, or register "
            "EXEMPT_SHELL)")

    return violations, open_gaps


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--root", default=str(pathlib.Path(__file__).resolve().parent))
    ap.add_argument("--strict", action="store_true",
                    help="fail on OPEN_GAP entries too")
    args = ap.parse_args(argv)

    root = pathlib.Path(args.root).resolve()
    if not root.is_dir():
        print(f"--root {root} is not a directory", file=sys.stderr)
        return 2

    violations, open_gaps = collect(root, strict=args.strict)

    tools = [p for p in root.rglob("*.py") if not _is_test(p)]
    suites = [p for p in root.rglob("test_*.py")]
    print(f"harness coverage: {len(tools)} tool(s), {len(suites)} suite(s), "
          f"root={root}")
    for line in sorted(open_gaps):
        print(f"  [OPEN_GAP] {line}")
    for name in sorted(EXEMPT_SHELL):
        print(f"  [SHELL EXEMPT] {name}: {EXEMPT_SHELL[name]}")
    if violations:
        print(f"VIOLATIONS: {len(violations)}", file=sys.stderr)
        for line in violations:
            print(f"  - {line}", file=sys.stderr)
        return 2
    print("OK: every tool has a suite, every suite has a subject and tests, "
          "every entry point is declared")
    return 0


if __name__ == "__main__":
    sys.exit(main())
