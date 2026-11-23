#!/usr/bin/env python3
"""Portable offline PipelineK runner: durations, resources and local HTML.

Accepts any local project with an existing .kts pipeline; no checkout of the
PipelineK source tree, network connection or hosted CI is needed. Python stdlib.
The installed distribution ships this beside pipelinek-local-report.py.
"""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import resource
import shutil
import subprocess
import sys
import time
import uuid

from importlib.util import module_from_spec, spec_from_file_location

REPORTER_PATH = Path(__file__).with_name("pipelinek-local-report.py")
ID = re.compile(r"^[a-zA-Z0-9_.-]{1,128}$")
MAX_LINE = 32_768


def reporter():
    if not REPORTER_PATH.is_file():
        raise ValueError("missing installed PipelineK local report tool")
    spec = spec_from_file_location("pipelinek_local_report", REPORTER_PATH)
    module = module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def project_history(project):
    state = Path(os.environ.get("XDG_STATE_HOME") or Path.home() / ".local" / "state")
    identity = hashlib.sha256(str(project.resolve()).encode()).hexdigest()[:16]
    return state / "pipelinek" / "projects" / identity / "reports"


def safe_dir(folder):
    folder.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(folder, 0o700)
    return folder


def launch_command(pipelinek, script, journal, control_root, extra):
    forbidden = {"--db", "--control-root", "--format", "--receipt", "--output-protocol"}
    if any(word.split("=", 1)[0] in forbidden for word in extra):
        raise ValueError("journal, control-root and output protocol are controlled by the metrics runner")
    return [pipelinek, "run", "--format", "json",
            "--db", str(journal), "--control-root", str(control_root),
            *extra, str(script)]


def consume_launch(stream):
    """Relay stdout unmodified; inspect at most one bounded JSON line for launch ID."""
    pending = bytearray()
    run_id = None
    while True:
        block = os.read(stream.fileno(), 8192)
        if not block:
            break
        sys.stdout.buffer.write(block)
        sys.stdout.buffer.flush()
        pending.extend(block)
        while b"\n" in pending:
            raw, _, rest = pending.partition(b"\n")
            pending = bytearray(rest)
            if len(raw) <= MAX_LINE:
                try:
                    item = json.loads(raw)
                    if item.get("schema") == "pipelinek.launch/1" and item.get("status") == "Launched":
                        value = item.get("runId")
                        if isinstance(value, str) and ID.fullmatch(value):
                            run_id = value
                except (UnicodeError, ValueError, AttributeError):
                    pass
        if len(pending) > MAX_LINE:
            pending.clear()
    return run_id


def child_resources(before, after, elapsed_seconds, exit_code):
    """RUSAGE_CHILDREN is attributable to this fresh wrapper's direct child.

    The CPU sum is for reaped subprocess accounting. ru_maxrss is the maximum
    individual child's RSS in KiB on Linux, NOT aggregate RSS across all
    parallel descendants and not a cgroup peak.
    """
    peak = after.ru_maxrss if sys.platform.startswith("linux") else None
    if before.ru_maxrss and peak and before.ru_maxrss >= peak:
        peak = None
    return {
        "measurement": "process-rusage-children-not-cgroup-attributed",
        "capturedAt": datetime.now(timezone.utc).isoformat(),
        "elapsedWallMs": max(0, round(elapsed_seconds * 1000)),
        "cpuUserMs": max(0, round((after.ru_utime - before.ru_utime) * 1000)),
        "cpuSystemMs": max(0, round((after.ru_stime - before.ru_stime) * 1000)),
        "maxSingleProcessRssKiB": peak,
        "exitCode": exit_code,
    }


def collect_typed_events(pipelinek, journal, run_id, store, profile, usage, snap_path):
    """Only sanitized summaries reach disk. Event JSONL goes through a pipe."""
    report = reporter()
    ingest = [sys.executable, str(REPORTER_PATH), "ingest",
              "--history-dir", str(store), "--workflow", profile,
              "--snapshot", str(snap_path), "--events", "-"]
    source = [pipelinek, "events", run_id, "--db", str(journal),
              "--typed", "--limit", "1000000"]
    with subprocess.Popen(source, stdout=subprocess.PIPE, stderr=subprocess.PIPE) as reader:
        with subprocess.Popen(ingest, stdin=reader.stdout, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, text=True) as projection:
            reader.stdout.close()
            result_out, result_err = projection.communicate()
        source_err = reader.stderr.read()
        source_exit = reader.wait()
    if source_exit != 0 or projection.returncode != 0:
        sys.stderr.write("Metrics event import incomplete or refused; no success report will be claimed\n")
        # These diagnostics are from the tools, not raw event messages.
        if source_exit != 0:
            sys.stderr.write("PipelineK events exit=" + str(source_exit) + "\n")
        if projection.returncode != 0:
            sys.stderr.write("Metrics projection exit=" + str(projection.returncode) + "\n")
        return None
    summary_path = store / "runs" / (hashlib.sha256(run_id.encode()).hexdigest() + ".json")
    if not summary_path.is_file():
        raise ValueError("metrics projection reported no summary")
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    summary["processUsage"] = usage
    report.json_write(summary_path, summary)
    return summary_path


def execute(args):
    project = Path(args.project).expanduser().resolve()
    if not project.is_dir():
        raise ValueError("project directory does not exist")
    script = Path(args.script)
    script = (project / script).resolve() if not script.is_absolute() else script.resolve()
    if not script.is_file():
        raise ValueError("pipeline script does not exist")
    if not (script == project or project in script.parents):
        raise ValueError("pipeline must be contained within the selected project")
    executable = args.pipelinek or shutil.which("pipelinek")
    if not executable:
        raise ValueError("pipelinek executable not installed (or use --pipelinek)")
    report = reporter()
    history = safe_dir(Path(args.history_dir).expanduser().resolve()
                       if args.history_dir else project_history(project))
    active = safe_dir(history / "executions" / str(uuid.uuid4()))
    journal = active / "journal.db"
    control_root = safe_dir(active / "control")
    snapshot = active / "resources-at-start.json"
    report.json_write(snapshot, report.resources(str(project)))
    command = launch_command(executable, script, journal, control_root, args.pipelinek_args)
    environment = os.environ.copy()
    environment["PIPELINE_CREDENTIALS_MASKING"] = "required"
    before = resource.getrusage(resource.RUSAGE_CHILDREN)
    started = time.monotonic()
    with subprocess.Popen(command, cwd=project, env=environment,
                          stdout=subprocess.PIPE, stderr=None) as process:
        run_id = consume_launch(process.stdout)
        exit_code = process.wait()
    usage = child_resources(before, resource.getrusage(resource.RUSAGE_CHILDREN),
                            time.monotonic() - started, exit_code)
    report.json_write(active / "process-usage.json", usage)
    if run_id:
        try:
            summary = collect_typed_events(
                executable, journal, run_id, history, args.workflow, usage, snapshot
            )
            if summary is not None:
                html_out = history / "dashboard.html"
                report.atomic(html_out, report.dashboard(report.history(history)))
                sys.stderr.write("PipelineK local metrics: " + str(summary) + "\n")
                sys.stderr.write("PipelineK local dashboard: " + str(html_out) + "\n")
        except (OSError, ValueError) as failure:
            sys.stderr.write("Metrics unavailable (pipeline exit retained): " + type(failure).__name__ + "\n")
    else:
        sys.stderr.write("No validated pipelinek.launch/1 receipt; metrics status UNKNOWN\n")
    return exit_code


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", default=".", help="any local project root")
    parser.add_argument("--script", default="pipeline.kts", help="relative pipeline script")
    parser.add_argument("--workflow", default="ci", help="stable workflow name, no secrets")
    parser.add_argument("--pipelinek", help="installed PipelineK binary; otherwise search PATH")
    parser.add_argument("--history-dir", help="override XDG state history location")
    parser.add_argument("pipelinek_args", nargs="*", help="extra PipelineK run flags after --")
    args = parser.parse_args()
    if args.pipelinek_args and args.pipelinek_args[0] == "--":
        args.pipelinek_args = args.pipelinek_args[1:]
    try:
        return execute(args)
    except (ValueError, OSError) as error:
        sys.stderr.write("PipelineK local metrics runner refused: " + str(error) + "\n")
        return 2


if __name__ == "__main__":
    sys.exit(main())
