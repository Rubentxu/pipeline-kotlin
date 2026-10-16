#!/usr/bin/env python3
"""Reproducible Gradle test baseline (mandate B1): runs, timings, counts, cache.

Why this exists
---------------
"P50/P95" claims need numbers bound to an argv and a tree, not impressions. This
tool runs a Gradle task N times, records per-run wall time, Gradle's own
reported time, the actionable-task split (executed / from-cache / up-to-date),
the JUnit counts of the module results directory and which XML files this run
actually rewrote — so a cache-warm "fast" run cannot be presented as a measured
improvement.

Timing breakdown (optional --profile) parses the newest Gradle profile HTML
(startup / settings+buildSrc / loading / configuring / execution / total), which
is where configuration-vs-execution lives.

Honesty rules
-------------
  * A FAILED run is recorded as a failure; the summary verdict is FAIL and the
    exit code is non-zero. There is no green summary over red samples.
  * `runs < 5` is reported as a small-sample median, not a statistical P50.
  * `--require-work` refuses (exit 2) when no run executed any task, because a
    fully cache-warm sample measures the cache, not the workload.

Usage:
    python3 scripts/measure-harness-baseline.py --task :pipeline-domain:test --runs 3
    python3 scripts/measure-harness-baseline.py --task :pipeline-domain:test --runs 5 \
        --profile --out "$JCODE_SCRATCH_DIR/baseline.json"

Exit codes:
    0  all samples succeeded
    1  at least one sample FAILED (recorded, not hidden)
    2  refusal (no work executed under --require-work, bad arguments)
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import math
import os
import pathlib
import re
import statistics
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent

_GRADLE_TIME = re.compile(r"BUILD (SUCCESSFUL|FAILED) in (\d+(?:\.\d+)?)\s*s")
# Gradle prints only the non-zero categories, e.g. "8 actionable tasks: 8 executed"
# or "13 actionable tasks: 10 executed, 3 up-to-date". Parse the tail generically.
_ACTIONABLE = re.compile(r"(\d+) actionable tasks?: ([^\n]+)")


def _parse_task_split(stdout: str) -> dict:
    split = {"executed": None, "from_cache": None, "up_to_date": None}
    m = _ACTIONABLE.search(stdout)
    if not m:
        return split
    split = {"executed": 0, "from_cache": 0, "up_to_date": 0, "other": 0}
    for part in m.group(2).split(","):
        mm = re.match(r"(\d+)\s+(.+)$", part.strip())
        if not mm:
            continue
        count = int(mm.group(1))
        label = mm.group(2).strip().lower()
        if label.startswith("executed"):
            split["executed"] = count
        elif label.startswith("from cache"):
            split["from_cache"] = count
        elif label.startswith("up-to-date"):
            split["up_to_date"] = count
        else:
            split["other"] += count
    return split


def _load_junit_report():
    """Reuse the run-scoped report tool instead of re-implementing XML reading."""
    path = ROOT / "scripts" / "run-scoped-junit-report.py"
    spec = importlib.util.spec_from_file_location("run_scoped_junit_report_mod", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def parse_gradle_output(stdout: str) -> dict:
    """Typed interpretation of one Gradle invocation's console output."""
    result = "UNKNOWN"
    reported_s = None
    m = _GRADLE_TIME.search(stdout)
    if m:
        # Gradle prints SUCCESSFUL/FAILED; normalise the success word.
        result = "SUCCESS" if m.group(1).upper() == "SUCCESSFUL" else "FAILED"
        reported_s = float(m.group(2))
    split = _parse_task_split(stdout)
    return {"result": result, "reported_s": reported_s, **split}


def parse_profile_html(path: pathlib.Path) -> dict | None:
    """Extract the summary timings from a Gradle profile HTML report."""
    try:
        text = path.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return None
    # The summary table renders rows like: <td>Configuring projects</td><td>1.234s</td>
    wanted = {
        "startup": "Startup",
        "settings_and_buildsrc": "Settings and buildSrc",
        "loading_projects": "Loading Projects",
        "configuring_projects": "Configuring Projects",
        "executing_tasks": "Task Execution",
        "total": "Total Build Time",
    }
    out: dict[str, float] = {}
    for key, label in wanted.items():
        m = re.search(re.escape(label) + r"</td>\s*<td[^>]*>\s*([0-9.]+)\s*s",
                      text, re.IGNORECASE)
        if m:
            out[key] = float(m.group(1))
    return out or None


def newest_profile(profile_root: pathlib.Path) -> pathlib.Path | None:
    if not profile_root.is_dir():
        return None
    files = sorted(profile_root.glob("profile-*.html"),
                   key=lambda p: p.stat().st_mtime)
    return files[-1] if files else None


def results_dir_for(task: str, repo_root: pathlib.Path,
                    override: pathlib.Path | None) -> pathlib.Path | None:
    if override is not None:
        return override
    m = re.fullmatch(r":([A-Za-z0-9_.-]+):test", task)
    if not m:
        return None
    return repo_root / "v2" / m.group(1) / "build" / "test-results" / "test"


def xml_snapshot(results_dir: pathlib.Path | None, run_started_at: float) -> dict:
    """JUnit totals for the module plus how many XML files this run rewrote."""
    if results_dir is None or not results_dir.is_dir():
        return {"dir": str(results_dir) if results_dir else None, "files": 0,
                "rewritten_files": 0, "tests": 0, "failures": 0, "errors": 0,
                "skipped": 0, "unreadable_files": 0}
    report = _load_junit_report()
    paths = report.discover(str(results_dir))
    totals = report.totals(paths)
    rewritten = [p for p in paths if p and os.path.getmtime(p) >= run_started_at]
    return {
        "dir": str(results_dir),
        "files": len(paths),
        "rewritten_files": len(rewritten),
        "tests": totals.get("tests", 0),
        "failures": totals.get("failures", 0),
        "errors": totals.get("errors", 0),
        "skipped": totals.get("skipped", 0),
        "unreadable_files": totals.get("unreadable_files", 0),
    }


def run_once(task: str, repo_root: pathlib.Path, *, profile: bool,
             timeout_s: int, runner=None) -> dict:
    argv = ["v2/gradlew", "-p", "v2", task, "--console=plain"]
    if profile:
        argv.append("--profile")
    started = time.monotonic()
    if runner is None:
        proc = subprocess.run(argv, cwd=repo_root, capture_output=True,
                              text=True, timeout=timeout_s)
        rc, stdout = proc.returncode, proc.stdout + proc.stderr
    else:
        rc, stdout = runner(argv)
    wall_s = round(time.monotonic() - started, 3)
    parsed = parse_gradle_output(stdout)
    sample = {
        "argv": argv,
        "exit_code": rc,
        "wall_s": wall_s,
        **parsed,
        "profile": None,
    }
    if profile:
        prof = newest_profile(repo_root / "v2" / "build" / "reports" / "profile")
        if prof is not None:
            sample["profile"] = {"file": str(prof), **(parse_profile_html(prof) or {})}
    return sample


def percentile(values: list[float], p: float) -> float | None:
    """Linear-interpolation percentile (the definition the report names).

    Kept as a pure function because the number is the product of this tool: a
    wrong percentile is a wrong baseline, and a baseline drives rule 4's budget.
    """
    if not values:
        return None
    if len(values) == 1:
        return round(float(values[0]), 3)
    ordered = sorted(values)
    rank = (len(ordered) - 1) * (p / 100.0)
    low = math.floor(rank)
    high = math.ceil(rank)
    if low == high:
        return round(float(ordered[int(rank)]), 3)
    fraction = rank - low
    return round(float(ordered[low] + (ordered[high] - ordered[low]) * fraction), 3)


def summarize(task: str, samples: list[dict], xml: dict, *, require_work: bool) -> dict:
    walls = [s["wall_s"] for s in samples]
    executed = [s.get("executed") or 0 for s in samples]
    failures = sum(1 for s in samples if s["exit_code"] != 0 or s["result"] == "FAILED")
    work_executed = any(e > 0 for e in executed) or xml.get("rewritten_files", 0) > 0
    n = len(samples)
    return {
        "task": task,
        "runs": n,
        "small_sample": n < 5,
        # A P95 out of five samples is the maximum with better manners; the flag
        # says so instead of letting the reader treat it as a tail estimate.
        "p95_reliable": n >= 20,
        "verdict": "FAIL" if failures else "PASS",
        "failures": failures,
        "wall_s": {
            "p50": percentile(walls, 50),
            "p95": percentile(walls, 95),
            "median": round(statistics.median(walls), 3) if walls else None,
            "mean": round(statistics.fmean(walls), 3) if walls else None,
            "stdev": (round(statistics.stdev(walls), 3)
                      if len(walls) > 1 else None),
            "min": round(min(walls), 3) if walls else None,
            "max": round(max(walls), 3) if walls else None,
            "samples": walls,
        },
        "work_executed": work_executed,
        "sum_task_splits": {
            "executed": sum(s.get("executed") or 0 for s in samples),
            "from_cache": sum(s.get("from_cache") or 0 for s in samples),
            "up_to_date": sum(s.get("up_to_date") or 0 for s in samples),
        },
        "xml": xml,
        "require_work": require_work,
    }


def format_report(task: str, samples: list[dict], summary: dict) -> str:
    lines = [f"BASELINE {task}  runs={summary['runs']}"
             + (" (small-sample median, not a statistical P50)" if summary["small_sample"] else "")]
    for i, s in enumerate(samples, 1):
        split = f"executed={s.get('executed')} cache={s.get('from_cache')} up-to-date={s.get('up_to_date')}"
        prof = ""
        if s.get("profile"):
            p = s["profile"]
            prof = (f"  [profile startup={p.get('startup')} config={p.get('configuring_projects')}"
                    f" exec={p.get('executing_tasks')} total={p.get('total')}]")
        lines.append(f"  run {i}: exit={s['exit_code']} {s['result']} "
                     f"wall={s['wall_s']}s gradle={s['reported_s']}s {split}{prof}")
    w = summary["wall_s"]
    tail = ("" if summary.get("p95_reliable")
            else " (p95 unreliable below 20 runs)")
    lines.append(f"  p50={w['p50']}s p95={w['p95']}s{tail} mean={w['mean']}s "
                 f"stdev={w['stdev']}s median={w['median']}s min={w['min']}s "
                 f"max={w['max']}s verdict={summary['verdict']} "
                 f"work_executed={summary['work_executed']}")
    lines.append(f"  xml: files={summary['xml']['files']} rewritten={summary['xml']['rewritten_files']} "
                 f"tests={summary['xml']['tests']} failures={summary['xml']['failures']} "
                 f"errors={summary['xml']['errors']} skipped={summary['xml']['skipped']} "
                 f"unreadable={summary['xml']['unreadable_files']}")
    return "\n".join(lines)


def main(argv=None, *, runner=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--task", action="append", required=True,
                    help="Gradle task, e.g. :pipeline-domain:test (repeatable)")
    ap.add_argument("--runs", type=int, default=3)
    ap.add_argument("--out", help="write the full JSON report here")
    ap.add_argument("--profile", action="store_true",
                    help="pass --profile and parse the timings breakdown")
    ap.add_argument("--timeout", type=int, default=1800)
    ap.add_argument("--results-dir", help="override the JUnit results directory")
    ap.add_argument("--require-work", action="store_true",
                    help="refuse (exit 2) when no run executed any task")
    args = ap.parse_args(argv)

    if args.runs < 1:
        print("--runs must be >= 1", file=sys.stderr)
        return 2

    report = {"tool": "scripts/measure-harness-baseline.py",
              "repo_root": str(ROOT), "tasks": []}
    refusal = False
    failed = False
    for task in args.task:
        results_dir = results_dir_for(task, ROOT,
                                      pathlib.Path(args.results_dir) if args.results_dir else None)
        samples = []
        for _ in range(args.runs):
            run_started = time.time()
            sample = run_once(task, ROOT, profile=args.profile, timeout_s=args.timeout,
                              runner=runner)
            sample["xml"] = xml_snapshot(results_dir, run_started)
            samples.append(sample)
        xml = samples[-1]["xml"] if samples else {}
        summary = summarize(task, samples, xml, require_work=args.require_work)
        if summary["verdict"] == "FAIL":
            failed = True
        if args.require_work and not summary["work_executed"]:
            refusal = True
            summary["verdict"] = "REFUSED_NO_WORK"
        report["tasks"].append({"summary": summary, "samples": samples})
        print(format_report(task, samples, summary))
        print()

    if args.out:
        pathlib.Path(args.out).write_text(json.dumps(report, indent=2, sort_keys=True))
        print(f"WROTE {args.out}")

    if refusal:
        print("REFUSED: no run executed any task (cache-warm sample is not a baseline)",
              file=sys.stderr)
        return 2
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
