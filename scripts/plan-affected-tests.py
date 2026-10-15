#!/usr/bin/env python3
"""Affected-test planner (B2 devGate): changed files -> one Gradle plan.

Single source of truth for "which tests does my diff touch". Both the advisory
`just changed` and the executing `just dev` consume this file, so the mapping
cannot exist twice and drift.

Rules (V2 critical path):
    docs/*, *.md, *.txt, *.json (outside v2)   -> nothing (docs-only)
    v2/compatibility/*                          -> :pipeline-application:test (corpus)
    v2/*.gradle.kts, v2/gradle/*, v2/gradlew*   -> check (build-system: full gate)
    v2/pipeline-step-sdk/<x>/*                  -> :pipeline-step-sdk:<x>:test
                                                   + :pipeline-application:test
    v2/pipeline-events/*                        -> :pipeline-events:test
                                                   + :pipeline-architecture-tests:test
    v2/<module>/* (module has build.gradle.kts) -> :<module>:test
    scripts/<tool>.py or scripts/test_*.py      -> the tool's python suite
    justfile, .agent/*                          -> nothing (tooling-only)
    anything else under v2                      -> UNCLASSIFIED (exit 2)

A plan never invents a module: `:<module>:test` is emitted only when
`v2/<module>/build.gradle.kts` exists. Anything the rules cannot place is
returned as UNCLASSIFIED and the planner exits 2 instead of guessing.

Usage:
    python3 scripts/plan-affected-tests.py --base HEAD
    python3 scripts/plan-affected-tests.py --base origin/main --format args
    python3 scripts/plan-affected-tests.py --files a/b.kt --files v2/pipeline-domain/X.kt

Exit codes:
    0  plan produced (possibly empty)
    2  unclassified productive path, or bad arguments
"""
from __future__ import annotations

import argparse
import json
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

BUILD_SYSTEM = ("v2/build.gradle.kts", "v2/settings.gradle.kts",
                "v2/gradle.properties", "v2/libs.versions.toml")
BUILD_SYSTEM_PREFIXES = ("v2/gradle/", "v2/buildSrc/")


def _is_docs(path: str) -> bool:
    if path.startswith("docs/"):
        return True
    return path.endswith((".md", ".txt")) and not path.startswith("v2/")


def _is_tooling_only(path: str) -> bool:
    return path == "justfile" or path.startswith(".agent/") or path.startswith(".github/")


def _module_has_build(root: pathlib.Path, module: str) -> bool:
    return (root / "v2" / module / "build.gradle.kts").is_file()


def _python_suite_for(path: str, root: pathlib.Path) -> list[str]:
    p = pathlib.Path(path)
    if p.suffix != ".py" or not path.startswith("scripts/"):
        return []
    if p.name.startswith("test_"):
        return [path] if (root / path).is_file() else []
    suite = p.parent / f"test_{p.stem.replace('-', '_')}.py"
    return [suite.as_posix()] if (root / suite).is_file() else []


def plan(files: list[str], root: pathlib.Path) -> dict:
    gradle: set[str] = set()
    python_suites: set[str] = set()
    unclassified: list[str] = []
    docs_only = bool(files)
    full_gate = False

    for path in files:
        path = path.strip().lstrip("./")
        if not path:
            continue
        if _is_docs(path):
            continue
        if _is_tooling_only(path):
            docs_only = False
            continue

        docs_only = False

        if path.startswith("scripts/"):
            suites = _python_suite_for(path, root)
            if suites:
                python_suites.update(suites)
            elif path.startswith("scripts/"):
                unclassified.append(path)
            continue

        if not path.startswith("v2/"):
            # Root-level non-doc configuration (workflows, scripts already
            # handled): treat as tooling, no test task.
            continue

        if path in BUILD_SYSTEM or path.startswith(BUILD_SYSTEM_PREFIXES) \
                or path.startswith("v2/gradlew"):
            full_gate = True
            continue

        rest = path[len("v2/"):]
        parts = rest.split("/")
        if len(parts) < 2:
            unclassified.append(path)
            continue

        if parts[0] == "compatibility":
            gradle.add(":pipeline-application:test")
            continue

        if parts[0] == "pipeline-step-sdk" and len(parts) >= 3:
            sdk_module = f"pipeline-step-sdk/{parts[1]}"
            if not _module_has_build(root, sdk_module):
                unclassified.append(path)
                continue
            gradle.add(f":pipeline-step-sdk:{parts[1]}:test")
            gradle.add(":pipeline-application:test")
            continue

        module = parts[0]
        if not _module_has_build(root, module):
            unclassified.append(path)
            continue
        gradle.add(f":{module}:test")
        if module == "pipeline-events":
            gradle.add(":pipeline-architecture-tests:test")

    if full_gate:
        gradle = {"check"}

    return {
        "gradle_tasks": sorted(gradle),
        "python_suites": sorted(python_suites),
        "docs_only": docs_only and not gradle and not python_suites,
        "full_gate": full_gate,
        "unclassified": sorted(set(unclassified)),
    }


def changed_files(base: str, root: pathlib.Path) -> list[str]:
    """Files that differ from `base`, INCLUDING untracked ones.

    `git diff --name-only` alone hides a brand-new file, so a change that
    consists only of new files would plan nothing.
    """
    diff = subprocess.run(["git", "diff", "--name-only", base], cwd=root,
                          capture_output=True, text=True, check=True).stdout
    untracked = subprocess.run(["git", "ls-files", "--others", "--exclude-standard"],
                               cwd=root, capture_output=True, text=True,
                               check=True).stdout
    files = {line.strip() for line in diff.splitlines() if line.strip()}
    files.update(line.strip() for line in untracked.splitlines() if line.strip())
    return sorted(files)


def format_text(result: dict) -> str:
    lines = []
    if result["docs_only"]:
        lines.append("docs-only change: markdown/link validation suffices")
    if result["full_gate"]:
        lines.append("build-system change: full round gate (just gate)")
    if result["gradle_tasks"]:
        lines.append("gradle plan: v2/gradlew -p v2 " + " ".join(result["gradle_tasks"]))
    for suite in result["python_suites"]:
        lines.append(f"python suite: python3 {suite}")
    for path in result["unclassified"]:
        lines.append(f"UNCLASSIFIED (no test task mapped): {path}")
    if not lines:
        lines.append("no test plan for this diff")
    return "\n".join(lines)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--base", default="HEAD", help="git revision to diff against")
    ap.add_argument("--files", action="append", default=None,
                    help="explicit file list (bypasses git; repeatable)")
    ap.add_argument("--format", choices=["text", "args", "py-args", "json"],
                    default="text")
    ap.add_argument("--root", default=str(ROOT))
    args = ap.parse_args(argv)

    root = pathlib.Path(args.root).resolve()
    files = args.files if args.files is not None else changed_files(args.base, root)
    result = plan(files, root)

    if args.format == "text":
        print(format_text(result))
    elif args.format == "args":
        print(" ".join(result["gradle_tasks"]))
    elif args.format == "py-args":
        print(" ".join(result["python_suites"]))
    else:
        print(json.dumps(result, indent=2, sort_keys=True))

    if result["unclassified"]:
        for path in result["unclassified"]:
            print(f"unclassified productive path: {path}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
