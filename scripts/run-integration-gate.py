#!/usr/bin/env python3
"""integrationGate (B3): compose the local evidence, one Gradle graph, typed receipt.

Phases
------
  P1  harness coverage fitness          (`fitness-harness-coverage.py`)
  P2  affected python suites            (from plan-affected-tests.py)
  P3  one Gradle invocation             (affected :module:test tasks, or the full
                                         round gate when the plan says so), UAT battery
                                         excluded, incremental, with a boundary file
                                         created BEFORE the run so the fresh JUnit
                                         evidence can be adjudicated

Verdict (typed, never inferred):
  PASS      every phase that ran exited 0
  FAIL      a phase exited non-zero
  BLOCKED   the plan has unclassified paths, or --expect-sha does not match HEAD
  STALE     the Gradle phase ran but produced no fresh JUnit XML inside the boundary
  NOT_RUN   a phase with nothing to do (recorded, not counted as a pass)

The receipt carries: HEAD SHA, tree state, base, the plan, each phase's argv/exit/
duration, and the fresh vs carried-over JUnit counts. It never re-runs a whole gate
to fill a field, and it never reports PASS over a phase that did not run.

Usage:
    python3 scripts/run-integration-gate.py --base HEAD
    python3 scripts/run-integration-gate.py --base origin/main --expect-sha <sha> \\
        --out "$JCODE_SCRATCH_DIR/integration-receipt.json"

Exit codes: 0 PASS, 1 FAIL, 2 BLOCKED, 3 STALE.
"""
from __future__ import annotations

import argparse
import datetime
import importlib.util
import json
import pathlib
import re
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent


def _load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _planner():
    return _load("plan_affected_tests_mod", "plan-affected-tests.py")


def _junit_report():
    return _load("run_scoped_junit_report_mod", "run-scoped-junit-report.py")


UAT_EXCLUDES = ("*UatLocal*,*UatCompat*,*UatDsl*,*UatStep*,*UatEvt*,*UatDurable*,"
                "*Lpr011*,*PublishHtml*,*StashOps*")


def git_sha(root: pathlib.Path, *args: str) -> str:
    return subprocess.run(["git", *args], cwd=root, capture_output=True,
                          text=True, check=True).stdout.strip()


def build_plan(base: str, root: pathlib.Path) -> dict:
    planner = _planner()
    files = planner.changed_files(base, root)
    result = planner.plan(files, root)
    result["changed_files"] = files
    return result


def run_phase(name: str, argv: list[str], root: pathlib.Path, *,
              timeout_s: int, runner=None) -> dict:
    started = time.monotonic()
    if runner is None:
        proc = subprocess.run(argv, cwd=root, capture_output=True, text=True,
                              timeout=timeout_s)
        rc = proc.returncode
    else:
        rc, _ = runner(argv)
    return {"phase": name, "argv": argv, "exit_code": rc,
            "duration_s": round(time.monotonic() - started, 3)}


def results_roots_for(gradle_tasks: list[str], root: pathlib.Path) -> list[pathlib.Path]:
    """JUnit results directories the planned tasks would write.

    `:mod:test` -> v2/mod/build/test-results/test; `check` (or anything
    unparsable) -> v2, where the report tool's own walker keeps only real
    build/test-results trees.
    """
    roots: list[pathlib.Path] = []
    for task in gradle_tasks:
        m = re.fullmatch(r":([A-Za-z0-9_.-]+):test", task)
        if not m:
            return [root / "v2"]
        roots.append(root / "v2" / m.group(1) / "build" / "test-results" / "test")
    return roots


def fresh_evidence(roots: list[pathlib.Path], boundary: float) -> dict:
    report = _junit_report()
    fresh: list[str] = []
    stale: list[str] = []
    for results_dir in roots:
        if not results_dir.is_dir():
            continue
        f, s = report.split_fresh(report.discover(str(results_dir)), boundary)
        fresh.extend(f)
        stale.extend(s)
    return {"dirs": [str(r) for r in roots],
            "fresh_files": len(fresh), "carried_over_files": len(stale),
            "fresh": report.totals(fresh), "carried_over": report.totals(stale)}


def verdict_of(phases: list[dict], evidence: dict | None,
               blocked_reason: str | None) -> str:
    if blocked_reason:
        return "BLOCKED"
    if any(p["exit_code"] != 0 for p in phases if p.get("ran")):
        return "FAIL"
    gradle_ran = any(p["phase"] == "gradle" and p.get("ran") for p in phases)
    if gradle_ran and (evidence is None or evidence["fresh_files"] == 0):
        return "STALE"
    return "PASS"


def format_report(receipt: dict) -> str:
    lines = [f"INTEGRATION GATE  head={receipt['head'][:12]}  verdict={receipt['verdict']}"]
    lines.append(f"  base={receipt['base']}  tree={receipt['tree']}  "
                 f"plan: gradle={receipt['plan']['gradle_tasks']} "
                 f"python={receipt['plan']['python_suites']}")
    if receipt.get("blocked_reason"):
        lines.append(f"  BLOCKED: {receipt['blocked_reason']}")
    for phase in receipt["phases"]:
        state = "ran" if phase.get("ran") else "NOT_RUN"
        lines.append(f"  [{state}] {phase['phase']}: exit={phase['exit_code']} "
                     f"{phase['duration_s']}s")
    if receipt.get("evidence"):
        ev = receipt["evidence"]
        lines.append(f"  fresh XML: {ev['fresh_files']} file(s) "
                     f"tests={ev['fresh'].get('tests')} failures={ev['fresh'].get('failures')} "
                     f"errors={ev['fresh'].get('errors')} skips={ev['fresh'].get('skipped')} "
                     f"(carried-over {ev['carried_over_files']})")
    return "\n".join(lines)


def main(argv=None, *, runner=None, evidence_fn=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--base", default="HEAD")
    ap.add_argument("--expect-sha", help="refuse (BLOCKED) when HEAD differs")
    ap.add_argument("--budget", type=int, default=900)
    ap.add_argument("--out", help="write the receipt JSON here")
    ap.add_argument("--root", default=str(ROOT))
    args = ap.parse_args(argv)

    root = pathlib.Path(args.root).resolve()
    head = git_sha(root, "rev-parse", "HEAD")
    tree = "dirty" if git_sha(root, "status", "--porcelain") else "clean"
    plan = build_plan(args.base, root)

    blocked_reason = None
    if plan["unclassified"]:
        blocked_reason = "unclassified paths: " + ", ".join(plan["unclassified"])
    elif args.expect_sha and args.expect_sha != head:
        blocked_reason = (f"--expect-sha {args.expect_sha[:12]} does not match "
                          f"HEAD {head[:12]}")

    phases: list[dict] = []
    evidence = None

    if blocked_reason is None:
        phases.append(run_phase(
            "fitness", ["python3", "scripts/fitness-harness-coverage.py"],
            root, timeout_s=args.budget, runner=runner))
        phases[-1]["ran"] = True

        for suite in plan["python_suites"]:
            phase = run_phase("python:" + suite, ["python3", suite], root,
                              timeout_s=args.budget, runner=runner)
            phase["ran"] = True
            phases.append(phase)

        if plan["gradle_tasks"]:
            boundary = time.time()
            gradle_argv = ["v2/gradlew", "-p", "v2", *plan["gradle_tasks"],
                           "-Pshard.excludes=" + UAT_EXCLUDES, "--console=plain"]
            phase = run_phase("gradle", gradle_argv, root,
                              timeout_s=args.budget, runner=runner)
            phase["ran"] = True
            phases.append(phase)
            roots = results_roots_for(plan["gradle_tasks"], root)
            collector = evidence_fn or (lambda rs, b: fresh_evidence(rs, b))
            evidence = collector(roots, boundary)
        else:
            phases.append({"phase": "gradle", "argv": [], "exit_code": 0,
                           "duration_s": 0.0, "ran": False})
    else:
        for name in ("fitness", "python", "gradle"):
            phases.append({"phase": name, "argv": [], "exit_code": 0,
                           "duration_s": 0.0, "ran": False})

    verdict = verdict_of(phases, evidence, blocked_reason)
    receipt = {
        "tool": "scripts/run-integration-gate.py",
        "timestamp": datetime.datetime.now(datetime.timezone.utc)
            .strftime("%Y-%m-%dT%H:%M:%SZ"),
        "head": head,
        "tree": tree,
        "base": args.base,
        "plan": {"gradle_tasks": plan["gradle_tasks"],
                 "python_suites": plan["python_suites"],
                 "changed_files": plan["changed_files"]},
        "blocked_reason": blocked_reason,
        "phases": phases,
        "evidence": evidence,
        "verdict": verdict,
    }
    print(format_report(receipt))

    if args.out:
        pathlib.Path(args.out).write_text(json.dumps(receipt, indent=2, sort_keys=True))
        print(f"WROTE {args.out}")

    return {"PASS": 0, "FAIL": 1, "BLOCKED": 2, "STALE": 3}[verdict]


if __name__ == "__main__":
    sys.exit(main())
