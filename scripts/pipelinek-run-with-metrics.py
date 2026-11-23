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
try:
    import resource
except ImportError:
    resource = None
import shutil
import subprocess
import sys
import time
import uuid

from importlib.util import module_from_spec, spec_from_file_location

REPORTER_PATH = Path(__file__).with_name("pipelinek-local-report.py")
RECOVERY_PATH = Path(__file__).with_name("pipelinek-resume-state.py")
ID = re.compile(r"^[a-zA-Z0-9_.-]{1,128}$")
MAX_LINE = 32_768


def reporter():
    if not REPORTER_PATH.is_file():
        raise ValueError("missing installed PipelineK local report tool")
    spec = spec_from_file_location("pipelinek_local_report", REPORTER_PATH)
    module = module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def recovery_module():
    if not RECOVERY_PATH.is_file():
        raise ValueError("installed distribution missing pipelinek-resume-state.py")
    spec = spec_from_file_location("pipelinek_resume_state", RECOVERY_PATH)
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


def launch_command(pipelinek, script, journal, control_root, extra, resume=False):
    forbidden = {"--db", "--control-root", "--format", "--receipt",
                 "--output-protocol", "--resume", "--rerun"}
    if any(word.split("=", 1)[0] in forbidden for word in extra):
        raise ValueError("journal, control-root and replay policy are controlled by the local runner")
    if "--plugin-jar" in extra and "--require-plugin-trust" not in extra:
        raise ValueError("external plugins require --require-plugin-trust")
    return [pipelinek, "run", "--format", "json",
            "--db", str(journal), "--control-root", str(control_root),
            *(["--resume"] if resume else []), *extra, str(script)]


def consume_launch(stream, on_launch=None):
    """Relay stdout unmodified; persist the launch ID as soon as observed."""
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
                except (UnicodeError, ValueError):
                    continue
                if isinstance(item, dict) and item.get("schema") == "pipelinek.launch/1" and item.get("status") == "Launched":
                    value = item.get("runId")
                    if isinstance(value, str) and ID.fullmatch(value):
                        run_id = value
                        if on_launch is not None:
                            # Persisting the launch receipt is part of crash safety:
                            # an I/O failure must propagate, never be masked as parsing.
                            on_launch(value)
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


def show_status(project, script, workflow, executable, history, recovery):
    """Diagnostic only. Never attempts a resume, cancel or recovery mutation."""
    sessions = []
    for path in sorted((history / "executions").glob("*/session.json")):
        try:
            record = json.loads(path.read_text(encoding="utf-8"))
            ident = record.get("identity", {})
            if record.get("schema") != recovery.SCHEMA or not isinstance(ident, dict):
                continue
            if ident.get("project") != hashlib.sha256(str(project.resolve()).encode()).hexdigest():
                continue
            if ident.get("workflow") != workflow or ident.get("script") != str(script.relative_to(project)):
                continue
            run_id = record.get("runId")
            journal_state = "unknown"
            if not run_id:
                try:
                    run_id = recovery.prior_run_id(path.parent, record)
                except (ValueError, OSError):
                    run_id = None
            if isinstance(run_id, str) and recovery.RUN_ID.fullmatch(run_id):
                try:
                    journal_state = recovery.run_state(executable, path.parent, run_id)
                except ValueError:
                    journal_state = "unobservable-or-refused"
            sessions.append({
                "session": path.parent.name,
                "runId": run_id,
                "storedStatus": record.get("status"),
                "journalState": journal_state,
                "attempts": record.get("attempts"),
                "workflow": workflow,
            })
        except (OSError, ValueError, TypeError):
            continue
    print(json.dumps({"schema": "pipelinek.local-sessions/v1",
                      "project": hashlib.sha256(str(project.resolve()).encode()).hexdigest()[:16],
                      "sessions": sessions}, sort_keys=True, ensure_ascii=False))
    return 0


def execute(args):
    project = Path(args.project).expanduser().resolve()
    if not project.is_dir():
        raise ValueError("project directory does not exist")
    script = Path(args.script)
    script = (project / script).resolve() if not script.is_absolute() else script.resolve()
    if not script.is_file() or project not in script.parents:
        raise ValueError("pipeline script must be contained within the selected project")
    if args.resume_session and not (args.resume or args.resume_or_new):
        raise ValueError("--session requires --resume or --resume-or-new")
    if args.status and (args.resume or args.resume_or_new or args.new):
        raise ValueError("--status cannot also execute or resume")
    if sum(bool(v) for v in (args.resume, args.resume_or_new, args.new)) > 1:
        raise ValueError("--resume, --resume-or-new and --new are mutually exclusive")

    executable = args.pipelinek or shutil.which("pipelinek")
    if not executable:
        raise ValueError("pipelinek executable not installed (or use --pipelinek)")
    executable = str(Path(executable).expanduser().resolve())
    if not ID.fullmatch(args.workflow):
        raise ValueError("workflow must be a stable non-secret ID (letters/digits/._-)")
    report = reporter()
    recovery = recovery_module()
    history_path = Path(args.history_dir).expanduser().resolve() if args.history_dir else project_history(project)
    if not args.status and (history_path == project or project in history_path.parents):
        raise ValueError("durable history must live outside project inputs; use XDG state and export HTML separately")
    history = safe_dir(history_path)
    if args.status:
        return show_status(project, script, args.workflow, executable, history, recovery)
    source = recovery.source_identity(project, script)
    version_hash = recovery.executable_identity(executable)
    identity = recovery.session_identity(
        project, script, args.workflow, version_hash, args.pipelinek_args, source
    )

    # The lock is held for the entire pipeline and its reporting lifecycle.
    # A crash releases it. A surviving child must still pass PipelineK's lease.
    with recovery.exclusive(history, args.workflow):
        if args.resume_or_new:
            try:
                recovery.pending(history, identity, args.resume_session)
            except ValueError as error:
                if "found 0" in str(error):
                    args.resume = False
                else:
                    raise
            else:
                args.resume = True
        if args.resume:
            active, manifest = recovery.pending(history, identity, args.resume_session)
            if source["mode"] != "git-visible" and not args.accept_unverified_inputs:
                raise ValueError("non-Git workspace has only a script fingerprint; "
                                 "explicit --accept-unverified-inputs required")
            if identity.get("unverifiedInputs") and not args.accept_unverified_inputs:
                raise ValueError("run uses configuration/credential files not verified by session identity; "
                                 "explicit --accept-unverified-inputs required")
            run_id = recovery.prior_run_id(active, manifest)
            recovery.preflight(executable, active, run_id)
            is_resume = True
        else:
            try:
                previous_dir, previous = recovery.pending(history, identity)
            except ValueError as error:
                if "found 0" not in str(error):
                    raise ValueError("unfinished or changed session requires manual reconciliation; "
                                     "refusing to start another run")
            else:
                if not args.new:
                    raise ValueError("unfinished session exists: use --resume or resolve it before starting fresh")
                # An explicit --new cannot bypass an unclosed external effect.
                # The older run must be terminal, either naturally or via
                # an operator-authorized 'runs cancel' / 'runs recover' action.
                previous_id = recovery.prior_run_id(previous_dir, previous)
                state = recovery.run_state(executable, previous_dir, previous_id)
                if not state.startswith("run-state terminal "):
                    raise ValueError("cannot start a second run while the prior run has no verified "
                                     "terminal; diagnose/cancel/recover it first")
                previous["status"] = "TERMINAL"
                report.json_write(previous_dir / "session.json", previous)
            session_id = str(uuid.uuid4())
            active = safe_dir(history / "executions" / session_id)
            manifest = recovery.new_manifest(identity, session_id)
            run_id = None
            is_resume = False

        journal = active / "journal.db"
        control_root = safe_dir(active / "control")
        snapshot = active / "resources-at-start.json"
        if not is_resume:
            report.json_write(snapshot, report.resources(str(project)))
        attempt = int(manifest.get("attempts", 0)) + 1
        attempt_snapshot = active / ("resources-at-attempt-" + str(attempt) + ".json")
        report.json_write(attempt_snapshot, report.resources(str(project)))
        command = launch_command(
            executable, script, journal, control_root, args.pipelinek_args, resume=is_resume
        )
        manifest["attempts"] = attempt
        manifest["status"] = "LAUNCH_REQUESTED"
        manifest["lastAttemptStartedAt"] = datetime.now(timezone.utc).isoformat()
        report.json_write(active / "session.json", manifest)
        environment = os.environ.copy()
        environment["PIPELINE_CREDENTIALS_MASKING"] = "required"
        before = resource.getrusage(resource.RUSAGE_CHILDREN) if resource else None
        started = time.monotonic()

        def on_launch(value):
            manifest["runId"] = value
            manifest["status"] = "RUNNING"
            report.json_write(active / "session.json", manifest)

        with subprocess.Popen(command, cwd=project, env=environment,
                              stdout=subprocess.PIPE, stderr=None) as process:
            reported_run_id = consume_launch(process.stdout, on_launch=on_launch)
            exit_code = process.wait()
        run_id = reported_run_id or run_id
        if resource:
            usage = child_resources(before, resource.getrusage(resource.RUSAGE_CHILDREN),
                                    time.monotonic() - started, exit_code)
        else:
            usage = {
                "measurement": "wall-time-only-process-rusage-unavailable",
                "capturedAt": datetime.now(timezone.utc).isoformat(),
                "elapsedWallMs": max(0, round((time.monotonic() - started) * 1000)),
                "cpuUserMs": None, "cpuSystemMs": None,
                "maxSingleProcessRssKiB": None, "exitCode": exit_code,
            }
        report.json_write(active / ("process-usage-attempt-" + str(attempt) + ".json"), usage)
        manifest["lastExitCode"] = exit_code
        manifest["lastAttemptFinishedAt"] = datetime.now(timezone.utc).isoformat()
        manifest["runId"] = run_id
        manifest["status"] = "UNKNOWN"
        if run_id:
            try:
                result = subprocess.run(
                    [executable, "inspect", run_id, "--state", "--db", str(journal),
                     "--control-root", str(control_root)],
                    stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=30,
                    check=False,
                )
                if result.returncode == 0:
                    state = result.stdout.decode("utf-8", "replace").strip()
                    if state.startswith("run-state terminal "):
                        manifest["status"] = "TERMINAL"
                    elif state in ("run-state running", "run-state empty", "run-state unobservable"):
                        manifest["status"] = "INTERRUPTED"
            except (OSError, subprocess.TimeoutExpired):
                pass
        report.json_write(active / "session.json", manifest)

        if run_id:
            try:
                summary = collect_typed_events(
                    executable, journal, run_id, history, args.workflow, usage, snapshot
                )
                if summary is not None:
                    summary_data = json.loads(Path(summary).read_text(encoding="utf-8"))
                    summary_data["resumeAttempts"] = attempt
                    # Durations spanning a crash may include offline downtime.
                    if attempt > 1:
                        summary_data["timingCaveat"] = "possible-gap-between-resume-attempts"
                    report.json_write(summary, summary_data)
                    html_out = history / "dashboard.html"
                    report.atomic(html_out, report.dashboard(report.history(history)))
                    sys.stderr.write("PipelineK local metrics: " + str(summary) + "\n")
                    sys.stderr.write("PipelineK local dashboard: " + str(html_out) + "\n")
            except (OSError, ValueError) as failure:
                sys.stderr.write("Metrics unavailable (pipeline exit retained): " +
                                 type(failure).__name__ + "\n")
        else:
            sys.stderr.write("No validated pipelinek.launch/1 receipt; metrics status UNKNOWN\n")
        if manifest["status"] != "TERMINAL":
            sys.stderr.write("Run has no verified terminal; resume with --resume after diagnosing "
                             "the durable journal (session " + active.name + ")\n")
        return exit_code

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", default=".", help="any local project root")
    parser.add_argument("--script", default="pipeline.kts", help="relative pipeline script")
    parser.add_argument("--workflow", default="ci", help="stable workflow name, no secrets")
    parser.add_argument("--pipelinek", help="installed PipelineK binary; otherwise search PATH")
    parser.add_argument("--history-dir", help="override XDG state history location")
    parser.add_argument("--resume", action="store_true", help="continue the interrupted durable run; never start a new one")
    parser.add_argument("--status", action="store_true", help="inspect local sessions and journal state without mutation")
    parser.add_argument("--resume-or-new", action="store_true",
                        help="resume the sole eligible interrupted session, otherwise start a fresh one; ambiguity refuses")
    parser.add_argument("--session", dest="resume_session", help="specific incomplete session UUID if several exist")
    parser.add_argument("--new", action="store_true", help="explicitly start a fresh run even when prior ones are unfinished")
    parser.add_argument("--accept-unverified-inputs", action="store_true",
                        help="allow resume in a non-Git project with only the script fingerprint")
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
