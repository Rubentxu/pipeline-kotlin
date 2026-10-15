#!/usr/bin/env python3
"""Gradle test-graph audit (mandate B1): what each test task drags in, at what policy.

Read-only. For each requested test task it runs `gradlew <task> --dry-run`
(configuration only, nothing executes) and reports:

  - HEAVY_IN_TEST        distribution / SBOM / plugin-JAR builders reachable from
                         a *test* task (rebuilt work a test rarely consumes)
  - DUPLICATE_HEAVY      the same heavy task reachable from several test tasks
  - TEST_ON_TEST         a test task whose graph contains another module's test
  - FORK_POLICY          maxParallelForks / forkEvery / useJUnitPlatform per module,
                         parsed from the build files (JVM-start cost surface)

Findings are printed for review; the audit itself does not fail. `--fail-on`
can turn a class into a gate once the owner decides it should be zero.

Usage:
    python3 scripts/audit-gradle-test-graph.py
    python3 scripts/audit-gradle-test-graph.py --task :pipeline-domain:test --json out.json
    python3 scripts/audit-gradle-test-graph.py --fail-on HEAVY_IN_TEST

Exit codes:
    0  audit produced (default)
    2  --fail-on class had findings, or bad arguments
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

DEFAULT_TASKS = [
    ":pipeline-domain:test",
    ":pipeline-events:test",
    ":pipeline-step-sdk:runtime:test",
    ":pipeline-architecture-tests:test",
    ":pipeline-application:test",
    ":pipeline-release:test",
]

_SKIPPED_LINE = re.compile(r"^(?:>\s*Task\s+)?(:[A-Za-z0-9_.:/-]+)\s+SKIPPED$")
_HEAVY_NAMES = re.compile(
    r":(installDist|distZip|cyclonedxBom|shadowJar|buildExamplePlugin|"
    r"buildExternalDirectivePlugin|buildExampleBlockPlugin)$")
_TEST_TASK = re.compile(r"^:([A-Za-z0-9_.-]+):test$")
_FORK = re.compile(r"forkEvery\s*=\s*(\d+)")
_PARALLEL = re.compile(r"maxParallelForks\s*=\s*(\d+)")
_PLATFORM = re.compile(r"useJUnitPlatform\s*\(\s*\)")


def parse_dry_run(stdout: str) -> list[str]:
    """Task paths that would execute (the `SKIPPED` tail of a --dry-run)."""
    tasks = []
    for line in stdout.splitlines():
        m = _SKIPPED_LINE.match(line.strip())
        if m:
            tasks.append(m.group(1))
    return tasks


def heavy_tasks(tasks: list[str]) -> list[str]:
    return sorted(t for t in tasks if _HEAVY_NAMES.search(t))


def test_on_test(tasks: list[str], subject: str) -> list[str]:
    return sorted(t for t in tasks
                  if t.endswith(":test") and t != subject and _TEST_TASK.match(t))


def findings_for_graphs(graphs: dict[str, list[str]]) -> list[dict]:
    findings: list[dict] = []
    heavy_seen: dict[str, list[str]] = {}
    for task, tasks in sorted(graphs.items()):
        for h in heavy_tasks(tasks):
            heavy_seen.setdefault(h, []).append(task)
        nested = test_on_test(tasks, task)
        if nested:
            findings.append({"class": "TEST_ON_TEST", "task": task, "detail": nested})
    for heavy, tasks in sorted(heavy_seen.items()):
        if len(tasks) > 1:
            findings.append({"class": "DUPLICATE_HEAVY", "task": heavy, "detail": tasks})
    for task, tasks in sorted(graphs.items()):
        heavy = heavy_tasks(tasks)
        if heavy:
            findings.append({"class": "HEAVY_IN_TEST", "task": task, "detail": heavy})
    return findings


def scan_fork_policy(root: pathlib.Path, *, scope: str = "v2") -> list[dict]:
    """Per-build-file test JVM policy (parallelism and recycling).

    Scoped to `v2/` by default: V1 modules are out of the V2 critical path and
    auditing them would mix two different builds.
    """
    scan_root = root / scope if (root / scope).is_dir() else root
    rows = []
    for build_file in sorted(scan_root.glob("**/build.gradle.kts")):
        if "/build/" in str(build_file):
            continue
        try:
            text = build_file.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        has_test = "tasks.test" in text or 'tasks.named("test")' in text
        if not has_test:
            continue
        parallel = _PARALLEL.search(text)
        fork = _FORK.search(text)
        rows.append({
            "module": build_file.relative_to(root).as_posix(),
            "maxParallelForks": int(parallel.group(1)) if parallel else None,
            "forkEvery": int(fork.group(1)) if fork else None,
            "useJUnitPlatform": bool(_PLATFORM.search(text)),
        })
    return rows


def dry_run(task: str, root: pathlib.Path, *, timeout_s: int, runner=None) -> list[str]:
    argv = ["v2/gradlew", "-p", "v2", task, "--dry-run", "--console=plain"]
    if runner is not None:
        rc, stdout = runner(argv)
    else:
        proc = subprocess.run(argv, cwd=root, capture_output=True, text=True,
                              timeout=timeout_s)
        rc, stdout = proc.returncode, proc.stdout + proc.stderr
    if rc != 0:
        raise RuntimeError(f"dry-run failed for {task} (exit {rc})")
    return parse_dry_run(stdout)


def format_report(graphs: dict[str, list[str]], findings: list[dict],
                  policy: list[dict]) -> str:
    lines = ["GRADLE TEST-GRAPH AUDIT", ""]
    for task, tasks in sorted(graphs.items()):
        heavy = heavy_tasks(tasks)
        lines.append(f"{task}: {len(tasks)} task(s) in graph"
                     + (f", heavy: {', '.join(heavy)}" if heavy else ""))
    lines.append("")
    lines.append("FINDINGS")
    if not findings:
        lines.append("  (none)")
    for f in findings:
        lines.append(f"  [{f['class']}] {f['task']}: {f['detail']}")
    lines.append("")
    lines.append("TEST JVM POLICY (from build files)")
    for row in policy:
        lines.append(f"  {row['module']}: maxParallelForks={row['maxParallelForks']} "
                     f"forkEvery={row['forkEvery']} useJUnitPlatform={row['useJUnitPlatform']}")
    return "\n".join(lines)


def main(argv=None, *, runner=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--task", action="append", help="test task (repeatable)")
    ap.add_argument("--root", default=str(ROOT))
    ap.add_argument("--timeout", type=int, default=300)
    ap.add_argument("--json", help="write the audit as JSON here")
    ap.add_argument("--fail-on", action="append", default=[],
                    choices=["HEAVY_IN_TEST", "DUPLICATE_HEAVY", "TEST_ON_TEST"],
                    help="exit 2 when this finding class is non-empty (repeatable)")
    ap.add_argument("--policy-scope", default="v2",
                    help="directory scanned for build-file JVM policy (default v2)")
    args = ap.parse_args(argv)

    root = pathlib.Path(args.root).resolve()
    tasks = args.task or DEFAULT_TASKS

    graphs: dict[str, list[str]] = {}
    for task in tasks:
        graphs[task] = dry_run(task, root, timeout_s=args.timeout, runner=runner)

    findings = findings_for_graphs(graphs)
    policy = scan_fork_policy(root, scope=args.policy_scope)
    report = format_report(graphs, findings, policy)
    print(report)

    if args.json:
        pathlib.Path(args.json).write_text(json.dumps(
            {"graphs": graphs, "findings": findings, "fork_policy": policy},
            indent=2, sort_keys=True))
        print(f"\nWROTE {args.json}")

    blocked = [f for f in findings if f["class"] in set(args.fail_on)]
    if blocked:
        print(f"\nFAIL: {len(blocked)} finding(s) in --fail-on classes",
              file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
