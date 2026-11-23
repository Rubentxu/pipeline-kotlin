#!/usr/bin/env python3
"""Offline PipelineK metrics from typed event JSONL. Stdlib only.

Commands: snapshot before launch, ingest after launch, dashboard for history.
Run files contain summaries, NEVER the original event payload or environment.
"""
from __future__ import annotations
import argparse
from collections import defaultdict, deque
from datetime import datetime, timezone
import hashlib
import html
import json
import os
from pathlib import Path
import platform
import re
import shutil
import statistics
import sys
import tempfile

SCHEMA = "pipelinek.local-metrics/v1"
SAFE = re.compile(r"^[A-Za-z0-9_ .:/-]{1,90}$")


def safe(value, fallback):
    return value if isinstance(value, str) and SAFE.fullmatch(value) and ".." not in value else fallback


def stamp(value):
    if not isinstance(value, str):
        raise ValueError("occurredAt missing")
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("event timestamp requires timezone")
    return parsed.timestamp()


def lines(path):
    if path == "-":
        yield from sys.stdin
    else:
        with open(path, encoding="utf-8") as stream:
            yield from stream


def project(lines_of_json, include_names=False):
    events = []
    for line in lines_of_json:
        if line.strip():
            entry = json.loads(line)
            if not isinstance(entry, dict) or "kind" not in entry or "sequence" not in entry:
                raise ValueError("expected PipelineK events --typed JSONL")
            events.append(entry)
    if not events:
        raise ValueError("no events")
    ids = {e.get("runId") for e in events}
    if len(ids) != 1 or not next(iter(ids)):
        raise ValueError("mixed or absent run IDs")
    seq = [e["sequence"] for e in events]
    if any(type(n) is not int or n < 1 for n in seq) or any(b <= a for a, b in zip(seq, seq[1:])):
        raise ValueError("event sequences must increase")
    started = next((e for e in events if e["kind"] == "RunStarted"), None)
    ended = next((e for e in reversed(events) if e["kind"] == "RunFinished"), None)
    if started is None:
        raise ValueError("RunStarted missing (possibly truncated history)")
    opens = defaultdict(deque)
    step_opens = defaultdict(deque)
    stages, steps = [], []
    for e in events:
        kind = e["kind"]
        if kind == "StageStarted":
            opens[e["stageIndex"]].append(e)
        elif kind == "StageFinished":
            queue = opens[e["stageIndex"]]
            if queue:
                beginning = queue.popleft()
                duration = round((stamp(e["occurredAt"]) - stamp(beginning["occurredAt"])) * 1000)
                if duration >= 0:
                    stages.append({
                        "stageIndex": beginning["stageIndex"],
                        "name": safe(beginning.get("stageName"), "stage-" + str(e["stageIndex"])) if include_names else "stage-" + str(e["stageIndex"]),
                        "startedAt": beginning["occurredAt"], "durationMs": duration,
                        "outcome": safe(e.get("outcome"), "unknown"),
                    })
        elif kind in ("StepStarted", "StepFinished", "StepFailed"):
            key = (e.get("stageIndex"), e.get("stepIndex"), e.get("opId") or e.get("stepName"))
            if kind == "StepStarted":
                step_opens[key].append(e)
            elif step_opens[key]:
                beginning = step_opens[key].popleft()
                duration = round((stamp(e["occurredAt"]) - stamp(beginning["occurredAt"])) * 1000)
                if duration >= 0:
                    steps.append({
                        "stageIndex": beginning.get("stageIndex"),
                        "name": safe(beginning.get("stepType"), "step") if include_names else "step",
                        "durationMs": duration,
                    })
    open_stages = sum(len(q) for q in opens.values())
    complete = bool(ended) and not open_stages
    total = round((stamp(ended["occurredAt"]) - stamp(started["occurredAt"])) * 1000) if complete else None
    if total is not None and total < 0:
        complete, total = False, None
    return {
        "schema": SCHEMA, "runId": str(started["runId"]),
        "startedAt": started["occurredAt"], "durationMs": total,
        "outcome": safe(ended.get("outcome"), "unknown") if complete else "incomplete",
        "coverage": "COMPLETE" if complete else "PARTIAL",
        "stages": sorted(stages, key=lambda row: row["startedAt"]),
        "steps": steps, "unmatchedStages": open_stages,
        "unmatchedSteps": sum(len(q) for q in step_opens.values()),
        "skippedStages": sum(e["kind"] == "StageSkipped" for e in events),
        "failedStepEvents": sum(e["kind"] == "StepFailed" for e in events),
        "lastSequence": seq[-1],
    }


def read_num(path):
    try:
        value = Path(path).read_text(encoding="utf-8").strip()
        return int(value) if value.isdecimal() else None
    except OSError:
        return None


def default_history_dir():
    workspace_key = hashlib.sha256(str(Path.cwd().resolve()).encode("utf-8")).hexdigest()[:16]
    root = Path(os.environ.get("XDG_STATE_HOME") or Path.home() / ".local" / "state")
    return root / "pipelinek" / "projects" / workspace_key / "reports"


def approved_snapshot(path):
    raw = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(raw, dict) or raw.get("measurement") != "start-capacity-not-process-consumption":
        raise ValueError("expected snapshot generated by snapshot command")
    cpu = raw.get("cpu", {})
    mem = raw.get("memory", {})
    disk = raw.get("workspaceDisk", {})
    if not all(isinstance(item, dict) for item in (cpu, mem, disk)):
        raise ValueError("invalid snapshot structure")
    # Never copy arbitrary content from a JSON supplied by another process.
    stamp(raw.get("capturedAt"))
    def numeric(value):
        if value is None:
            return None
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not 0 <= value < 1e18:
            raise ValueError("snapshot numeric resource field is invalid")
        return value

    load = cpu.get("loadAverage1m5m15m")
    if load is not None and (not isinstance(load, list) or len(load) != 3):
        raise ValueError("snapshot loadAverage shape is invalid")
    return {
        "capturedAt": raw["capturedAt"],
        "measurement": "start-capacity-not-process-consumption",
        "cpu": {
            "logicalHostCount": numeric(cpu.get("logicalHostCount")),
            "affinityCount": numeric(cpu.get("affinityCount")),
            "quotaCores": numeric(cpu.get("quotaCores")),
            "loadAverage1m5m15m": [numeric(v) for v in load] if load is not None else None,
        },
        "memory": {k: numeric(mem.get(k)) for k in
                   ("hostTotalBytes", "hostAvailableBytes", "cgroupLimitBytes", "cgroupCurrentBytes")},
        "workspaceDisk": {k: numeric(disk.get(k)) for k in ("totalBytes", "freeBytes")},
    }


def resources(workspace):
    try:
        meminfo = Path("/proc/meminfo").read_text(encoding="utf-8").splitlines()
        mem = {s.split(":")[0]: int(s.split(":")[1].split()[0]) * 1024 for s in meminfo if ":" in s}
    except (OSError, ValueError, IndexError):
        mem = {}
    try:
        disk = shutil.disk_usage(workspace)
        diskinfo = {"totalBytes": disk.total, "freeBytes": disk.free}
    except OSError:
        diskinfo = {"totalBytes": None, "freeBytes": None}
    try:
        affinity = len(os.sched_getaffinity(0))
    except (AttributeError, OSError):
        affinity = None
    try:
        load = [round(v, 2) for v in os.getloadavg()]
    except OSError:
        load = None
    try:
        quota = Path("/sys/fs/cgroup/cpu.max").read_text(encoding="utf-8").split()
        quota = round(int(quota[0]) / int(quota[1]), 2) if len(quota) == 2 and quota[0].isdecimal() and int(quota[1]) > 0 else None
    except (OSError, ValueError):
        quota = None
    return {
        "capturedAt": datetime.now(timezone.utc).isoformat(),
        "measurement": "start-capacity-not-process-consumption",
        "architecture": platform.machine(),
        "cpu": {"logicalHostCount": os.cpu_count(), "affinityCount": affinity,
                "quotaCores": quota, "loadAverage1m5m15m": load},
        "memory": {"hostTotalBytes": mem.get("MemTotal"),
                   "hostAvailableBytes": mem.get("MemAvailable"),
                   "cgroupLimitBytes": read_num("/sys/fs/cgroup/memory.max"),
                   "cgroupCurrentBytes": read_num("/sys/fs/cgroup/memory.current")},
        "workspaceDisk": diskinfo,
    }


def atomic(path, content):
    path = Path(path)
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(path.parent, 0o700)
    descriptor, temp = tempfile.mkstemp(prefix=".pipelinek-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            output.write(content)
        os.chmod(temp, 0o600)
        os.replace(temp, path)
    finally:
        if os.path.exists(temp):
            os.unlink(temp)


def json_write(path, value):
    atomic(path, json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n")


def history(folder):
    out = []
    for path in (Path(folder) / "runs").glob("*.json"):
        try:
            row = json.loads(path.read_text(encoding="utf-8"))
            if row.get("schema") == SCHEMA:
                out.append(row)
        except (OSError, ValueError):
            continue
    return sorted(out, key=lambda row: row.get("startedAt", ""))


def cell(value):
    return "<td>" + html.escape(str(value)) + "</td>"


def dashboard(rows):
    run_rows = []
    stages = defaultdict(list)
    for row in rows:
        initial = row.get("resourcesAtStart", {})
        cpu = initial.get("cpu", {})
        memory = initial.get("memory", {})
        configuration = (
            row.get("workflow", "workflow"),
            str(cpu.get("affinityCount", "unknown")),
            str(cpu.get("quotaCores", "unknown")),
            str(memory.get("cgroupLimitBytes", "unknown")),
        )
        for stage in row.get("stages", []):
            stages[configuration + (stage["name"],)].append(stage["durationMs"])
    for row in rows[-100:]:
        cpu = row.get("resourcesAtStart", {}).get("cpu", {})
        mem = row.get("resourcesAtStart", {}).get("memory", {})
        run_rows.append("<tr>" + "".join(map(cell, [
            row.get("startedAt"), row.get("workflow"), row.get("outcome"),
            str(round(row["durationMs"]/1000, 2)) + " s" if row.get("durationMs") is not None else "no medido",
            row.get("runId", "")[:18], cpu.get("affinityCount", "—"),
            mem.get("cgroupLimitBytes", "—"),
        ])) + "</tr>")
    stage_rows = []
    for key, samples in sorted(stages.items()):
        workflow, affinity, quota, memory_limit, name = key
        label = f"{workflow} / {name} (CPU affinity={affinity}, quota={quota}, memory.max={memory_limit})"
        samples = samples[-50:]
        minimum, maximum = min(samples), max(samples)
        scale = max(maximum - minimum, 1)
        points = " ".join(f"{10 + i * 260/max(len(samples)-1,1):.1f},{60-(v-minimum)*45/scale:.1f}" for i,v in enumerate(samples))
        trend = '<svg width="280" height="70" viewBox="0 0 280 70" role="img" aria-label="tendencia de duración"><polyline fill="none" stroke="#7cd6ed" stroke-width="2" points="' + points + '"/></svg>'
        previous = samples[-10:-5]
        current = samples[-5:]
        if len(previous) == 5 and len(current) == 5 and statistics.median(previous) > 0:
            change = (statistics.median(current) / statistics.median(previous) - 1.0) * 100
            progression = f"{change:+.1f}%"
        else:
            progression = "muestras insuficientes"
        stage_rows.append("<tr>" + cell(label) + cell(len(samples)) +
                          cell(str(round(statistics.median(samples)/1000,2)) + " s") +
                          cell(progression) + "<td>" + trend + "</td></tr>")
    return """<!doctype html><html lang="es"><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>PipelineK · métricas locales</title><style>body{background:#121b23;color:#f1f5f9;font:15px system-ui;margin:2rem auto;max-width:1300px;padding:0 1rem}table{border-collapse:collapse;width:100%}td,th{padding:10px;border-bottom:1px solid #364650;text-align:left;font-size:13px}section{overflow-x:auto}p{color:#c4d0d9}</style>
<h1>PipelineK · métricas locales</h1>
<p>Duraciones derivadas de eventos durables; recursos de inicio = capacidad/carga previa, no consumo del run. No incluye logs, variables de entorno ni servicios externos.</p>
<h2>Histórico de ejecuciones</h2><section><table><tr><th>Inicio</th><th>Workflow</th><th>Resultado</th><th>Duración</th><th>Run ID</th><th>CPU afinidad</th><th>Memoria límite cgroup (bytes)</th></tr>""" + "\n".join(run_rows) + """</table></section>
<h2>Evolución de duración por fase y capacidad inicial</h2><section><table><tr><th>Fase</th><th>Muestras (hasta 50)</th><th>Mediana</th><th>Últimas 5 frente a 5 anteriores</th><th>Evolución</th></tr>""" + "\n".join(stage_rows) + "</table></section></html>"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    snap = commands.add_parser("snapshot")
    snap.add_argument("--out", required=True)
    snap.add_argument("--workspace", default=".")
    ingest = commands.add_parser("ingest")
    ingest.add_argument("--events", default="-")
    ingest.add_argument("--snapshot")
    ingest.add_argument("--workflow", required=True)
    ingest.add_argument("--history-dir", default=str(default_history_dir()))
    ingest.add_argument("--include-names", action="store_true", help="opt in to storing stage/step names from trusted DSL")
    dash = commands.add_parser("dashboard")
    dash.add_argument("--history-dir", default=str(default_history_dir()))
    dash.add_argument("--out")
    args = parser.parse_args()
    if args.command == "snapshot":
        json_write(args.out, resources(args.workspace))
        print(args.out)
    elif args.command == "ingest":
        report = project(lines(args.events), include_names=args.include_names)
        report["workflow"] = safe(args.workflow, "workflow")
        if args.snapshot:
            snapshot = approved_snapshot(args.snapshot)
            lag_seconds = stamp(report["startedAt"]) - stamp(snapshot["capturedAt"])
            report["resourceSnapshotLagSeconds"] = round(lag_seconds, 2)
            if 0 <= lag_seconds <= 900:
                report["resourcesAtStart"] = snapshot
                report["resourceSnapshotStatus"] = "BEFORE_RUN"
            else:
                report["resourceSnapshotStatus"] = "LATE_OR_STALE"
                # Never label an old or late capture as the run's initial resources.
                report["resourcesAtStart"] = {}
        dest = Path(args.history_dir) / "runs" / (hashlib.sha256(report["runId"].encode()).hexdigest() + ".json")
        json_write(dest, report)
        print(dest)
    else:
        out = args.out or str(Path(args.history_dir) / "dashboard.html")
        atomic(out, dashboard(history(args.history_dir)))
        print(out)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError) as error:
        sys.exit("local metrics: " + str(error))
