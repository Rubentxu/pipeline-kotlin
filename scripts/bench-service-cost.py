#!/usr/bin/env python3
"""Measures what the local service costs and what it saves.

This is a measurement, not a gate: it asserts nothing about the figures it
produces and has no thresholds. It exists so that the claim "the socket brings
latency value" is a table somebody can reproduce instead of a sentence.

WHAT IT SEPARATES
-----------------

Two different costs hide behind the word "latency":

* The cost of STARTING A PROCESS: every CLI verb is a JVM that starts, does
  its work and exits (`cli_version`, `cli_run`, `cli_inspect_*`).
* The cost of ASKING A PROCESS THAT IS ALREADY RUNNING: a request over the
  Unix socket of `pipelinek serve` (`service_queries`).

A run started through the service still costs a JVM, because the service
launches the same `pipelinek run` the operator would type: the socket does not
make a run faster, it makes the QUESTIONS about runs cheaper and it keeps the
asker from owning the run (`service_runs` against `cli_run`). The report keeps
both numbers apart on purpose.

WHAT IT MEASURES
----------------

* environment: CPU, cores, memory, kernel, JDK, git revision, launcher cache.
* cli_version / cli_run / cli_inspect_*: wall time per process, peak RSS of
  the process (`wait4`), first run apart from the rest.
* service_startup: spawn of `serve` until its socket answers `version`.
* service_queries: p50/p95 of `version`, `health` and `run.state`, one
  connection per request, sequential and with concurrent clients.
* service_runs: `run.start` admission latency, and start-to-terminal time for
  sequential runs and for a batch with several runs in flight.
* resources: resident memory, threads and open descriptors of the service JVM
  at idle, after the queries and after the runs, and what is left after the
  service is told to stop (processes, socket file).

HOW IT MEASURES
---------------

Wall time is `time.perf_counter_ns` around the call. A query includes the
`connect`, the request line, the answer line and the `close`: that is what a
client that does not keep connections pays. The terminal of a run is found by
polling `run.state` every `POLL_INTERVAL_MS`, so the start-to-terminal figures
carry that resolution. Percentiles are nearest-rank over the raw samples,
which are all in the report.

The token never travels in argv or in the report: it lives in a file with
mode 0600 inside the work directory, the way the service asks for it.

USAGE
-----

    scripts/bench-service-cost.py --install DIR --workdir DIR --out FILE \
        [--runs 100] [--queries 500] [--version-runs 30] [--concurrency 4] \
        [--batch-runs 20]

`--install` is a distribution as produced by `./gradlew :pipeline-application:installDist`
(the directory that contains `bin/pipelinek`). `--workdir` must not exist or
must be empty; the script creates everything it needs inside it and removes
nothing that is not its own.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import platform
import shutil
import signal
import socket
import statistics
import subprocess
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

SCHEMA = "pipelinek.service/2"
POLL_INTERVAL_MS = 25
SAMPLE_EVERY = 25
RUN_DEADLINE_S = 180
STARTUP_DEADLINE_S = 60
STOP_DEADLINE_S = 30
HELLO_SCRIPT = (
    'pipeline {\n    stages {\n        stage("hello") {\n            echo("PIPELINE-OK")\n        }\n    }\n}\n'
)


def percentile(sorted_values: list[float], fraction: float) -> float:
    """Nearest-rank percentile of an already sorted, non-empty list."""
    rank = max(1, math.ceil(fraction * len(sorted_values)))
    return sorted_values[rank - 1]


def summarize(values: list[float]) -> dict:
    """The figures the report states for one series of milliseconds."""
    if not values:
        return {"n": 0}
    ordered = sorted(values)
    return {
        "n": len(ordered),
        "min": round(ordered[0], 2),
        "p50": round(percentile(ordered, 0.50), 2),
        "p95": round(percentile(ordered, 0.95), 2),
        "max": round(ordered[-1], 2),
        "mean": round(statistics.fmean(ordered), 2),
    }


def ms_since(start_ns: int) -> float:
    return (time.perf_counter_ns() - start_ns) / 1_000_000.0


# ---------------------------------------------------------------- environment


def os_name() -> str:
    try:
        with open("/etc/os-release", encoding="utf-8") as handle:
            for line in handle:
                if line.startswith("PRETTY_NAME="):
                    return line.split("=", 1)[1].strip().strip('"')
    except OSError:
        pass
    return platform.system()


def cpu_model() -> str:
    try:
        with open("/proc/cpuinfo", encoding="utf-8") as handle:
            for line in handle:
                if line.startswith("model name"):
                    return line.split(":", 1)[1].strip()
    except OSError:
        pass
    return platform.processor() or "unknown"


def mem_total_kib() -> int:
    try:
        with open("/proc/meminfo", encoding="utf-8") as handle:
            for line in handle:
                if line.startswith("MemTotal:"):
                    return int(line.split()[1])
    except OSError:
        pass
    return 0


def command_output(argv: list[str], cwd: str | None = None) -> str:
    try:
        done = subprocess.run(argv, capture_output=True, text=True, timeout=30, cwd=cwd, check=False)
    except (OSError, subprocess.SubprocessError):
        return "unknown"
    return (done.stdout + done.stderr).strip()


def environment(install: Path, repo_root: Path) -> dict:
    launcher = install / "bin" / "pipelinek"
    archives = sorted(str(p.name) for p in (install / "lib").glob("*.jsa")) if (install / "lib").is_dir() else []
    java = command_output(["java", "-version"]).splitlines()
    return {
        "cpu": cpu_model(),
        "cores": os.cpu_count(),
        "memoryTotalMiB": mem_total_kib() // 1024,
        "kernel": platform.release(),
        "os": os_name(),
        "javaOnPath": java[1] if len(java) > 1 else (java[0] if java else "unknown"),
        "gitRevision": command_output(["git", "rev-parse", "HEAD"], cwd=str(repo_root)),
        "gitDirty": bool(command_output(["git", "status", "--porcelain"], cwd=str(repo_root))),
        "productVersion": command_output([str(launcher), "version"]),
        "sharedClassArchives": archives,
        "pollIntervalMs": POLL_INTERVAL_MS,
        "python": platform.python_version(),
    }


# ------------------------------------------------------------------- processes


def run_cli(argv: list[str], env: dict, cwd: Path) -> dict:
    """Runs one process to its end and reports wall time, exit code and peak RSS."""
    start = time.perf_counter_ns()
    with open(os.devnull, "wb") as sink:
        proc = subprocess.Popen(argv, stdout=sink, stderr=sink, env=env, cwd=str(cwd))
        _, status, usage = os.wait4(proc.pid, 0)
    elapsed = ms_since(start)
    code = os.waitstatus_to_exitcode(status)
    proc.returncode = code
    return {"ms": round(elapsed, 2), "exit": code, "peakRssMiB": round(usage.ru_maxrss / 1024.0, 1)}


def children_of(pid: int) -> list[int]:
    """Live processes whose parent chain reaches [pid], excluding [pid]."""
    parents: dict[int, int] = {}
    for entry in os.listdir("/proc"):
        if not entry.isdigit():
            continue
        try:
            with open(f"/proc/{entry}/stat", encoding="utf-8") as handle:
                tail = handle.read().rsplit(")", 1)[1].split()
            parents[int(entry)] = int(tail[1])
        except (OSError, IndexError, ValueError):
            continue
    found: list[int] = []
    frontier = [pid]
    while frontier:
        current = frontier.pop()
        for child, parent in parents.items():
            if parent == current and child not in found:
                found.append(child)
                frontier.append(child)
    return found


def process_state(pid: int) -> dict:
    """Resident memory, threads, descriptors and children of a live process."""
    state: dict = {}
    try:
        with open(f"/proc/{pid}/status", encoding="utf-8") as handle:
            for line in handle:
                if line.startswith("VmRSS:"):
                    state["rssMiB"] = round(int(line.split()[1]) / 1024.0, 1)
                elif line.startswith("VmHWM:"):
                    state["peakRssMiB"] = round(int(line.split()[1]) / 1024.0, 1)
                elif line.startswith("Threads:"):
                    state["threads"] = int(line.split()[1])
        state["openFds"] = len(os.listdir(f"/proc/{pid}/fd"))
    except OSError:
        return {"alive": False}
    state["alive"] = True
    state["children"] = len(children_of(pid))
    return state


# --------------------------------------------------------------------- service


class Client:
    """One connection per request, the way the service is meant to be asked."""

    def __init__(self, socket_path: Path, token: str) -> None:
        self.socket_path = str(socket_path)
        self.token = token

    def call(self, op: str, **fields: object) -> tuple[dict, float]:
        request = {"schema": SCHEMA, "op": op, "auth": self.token, **fields}
        line = (json.dumps(request) + "\n").encode("utf-8")
        start = time.perf_counter_ns()
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as conn:
            conn.settimeout(30)
            conn.connect(self.socket_path)
            conn.sendall(line)
            buffer = b""
            while not buffer.endswith(b"\n"):
                chunk = conn.recv(65536)
                if not chunk:
                    break
                buffer += chunk
        elapsed = ms_since(start)
        return json.loads(buffer.decode("utf-8")), elapsed


def is_terminal(answer: dict) -> bool:
    return answer.get("kind") == "answer" and answer.get("result", {}).get("state") == "terminal"


def wait_terminal(client: Client, run_id: str) -> tuple[bool, str, float]:
    """Polls until the run is terminal. A run that is still held answers a refusal, not a state."""
    start = time.perf_counter_ns()
    deadline = time.monotonic() + RUN_DEADLINE_S
    while time.monotonic() < deadline:
        answer, _ = client.call("run.state", runId=run_id)
        if is_terminal(answer):
            return True, str(answer["result"].get("outcome")), ms_since(start)
        time.sleep(POLL_INTERVAL_MS / 1000.0)
    return False, "deadline", ms_since(start)


def start_and_wait(client: Client, script: str, key: str) -> dict:
    """Starts a run and waits for its terminal.

    A `too-many-supervised-runs` refusal is retried and counted: the terminal of
    a run is visible a moment before the supervisor frees its slot, so a client
    that starts the next run at once can be told the service is full.
    """
    begun = time.perf_counter_ns()
    retries = 0
    deadline = time.monotonic() + RUN_DEADLINE_S
    while True:
        attempt = time.perf_counter_ns()
        answer, _ = client.call("run.start", script=script, idempotencyKey=key)
        admission = ms_since(attempt)
        refusal = answer.get("refusal", {}).get("kind") if answer.get("kind") != "answer" else None
        if refusal != "too-many-supervised-runs" or time.monotonic() > deadline:
            break
        retries += 1
        time.sleep(POLL_INTERVAL_MS / 1000.0)
    if answer.get("kind") != "answer":
        return {"ok": False, "reason": refusal or "unknown", "admissionMs": admission, "busyRetries": retries}
    run_id = answer["result"]["runId"]
    done, outcome, _ = wait_terminal(client, run_id)
    return {
        "ok": done and outcome == "succeeded",
        "outcome": outcome,
        "runId": run_id,
        "admissionMs": round(admission, 2),
        "totalMs": round(ms_since(begun), 2),
        "busyRetries": retries,
    }


def wait_for_socket_answer(client: Client, popen: subprocess.Popen) -> bool:
    deadline = time.monotonic() + STARTUP_DEADLINE_S
    while time.monotonic() < deadline:
        if popen.poll() is not None:
            return False
        try:
            answer, _ = client.call("version")
            if answer.get("kind") == "answer":
                return True
        except (OSError, ValueError):
            pass
        time.sleep(0.005)
    return False


def query_series(client: Client, op: str, count: int, **fields: object) -> list[float]:
    return [client.call(op, **fields)[1] for _ in range(count)]


def concurrent_queries(client: Client, op: str, total: int, workers: int, **fields: object) -> dict:
    per_worker = max(1, total // workers)
    samples: list[float] = []
    lock = threading.Lock()

    def work() -> None:
        local = query_series(client, op, per_worker, **fields)
        with lock:
            samples.extend(local)

    start = time.perf_counter_ns()
    with ThreadPoolExecutor(max_workers=workers) as pool:
        for future in [pool.submit(work) for _ in range(workers)]:
            future.result()
    wall = ms_since(start)
    return {
        "workers": workers,
        "requests": len(samples),
        "wallMs": round(wall, 2),
        "requestsPerSecond": round(len(samples) / (wall / 1000.0), 1),
        "latencyMs": summarize(samples),
    }


# ------------------------------------------------------------------------ main


def prepare(workdir: Path) -> tuple[dict, str]:
    if workdir.exists() and any(workdir.iterdir()):
        sys.exit(f"bench-service-cost: {workdir} is not empty")
    workdir.mkdir(parents=True, exist_ok=True)
    os.chmod(workdir, 0o700)
    layout = {
        "scripts": workdir / "scripts",
        "cli": workdir / "cli",
        "state": workdir / "state",
        "sock": workdir / "sock",
        "cache": workdir / "script-cache",
    }
    for path in layout.values():
        path.mkdir()
    os.chmod(layout["sock"], 0o700)
    (layout["scripts"] / "hello.pipeline.kts").write_text(HELLO_SCRIPT, encoding="utf-8")
    token = os.urandom(24).hex()
    auth = workdir / "authorization"
    descriptor = os.open(auth, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
        handle.write(f"{token} bench all+start\n")
    layout["auth"] = auth
    return layout, token


def cli_run_argv(launcher: Path, state: Path, script: Path) -> list[str]:
    return [
        str(launcher), "run",
        "--db", str(state / "pipelinek.db"),
        "--control-root", str(state / "ctl"),
        "--format", "jsonl", "--rerun", "--isolated",
        str(script),
    ]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--install", required=True, type=Path)
    parser.add_argument("--workdir", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--runs", type=int, default=100)
    parser.add_argument("--queries", type=int, default=500)
    parser.add_argument("--version-runs", type=int, default=30)
    parser.add_argument("--concurrency", type=int, default=4)
    parser.add_argument("--batch-runs", type=int, default=20)
    args = parser.parse_args()

    launcher = (args.install / "bin" / "pipelinek").resolve()
    if not os.access(launcher, os.X_OK):
        sys.exit(f"bench-service-cost: {launcher} is not an executable launcher")
    repo_root = Path(__file__).resolve().parent.parent
    layout, token = prepare(args.workdir.resolve())
    env = dict(os.environ)
    env["PIPELINEK_SCRIPT_CACHE_DIR"] = str(layout["cache"])
    env.pop("PIPELINEK_OTEL_ENABLED", None)
    hello = layout["scripts"] / "hello.pipeline.kts"
    report: dict = {"environment": environment(args.install, repo_root)}
    report["environment"]["loadAverageAtStart"] = [round(v, 2) for v in os.getloadavg()]
    report["parameters"] = {
        "runs": args.runs, "queries": args.queries, "versionRuns": args.version_runs,
        "concurrency": args.concurrency, "batchRuns": args.batch_runs,
        "scriptCache": "isolated directory inside the work directory; the first compile is the only cache miss",
    }

    print("cli_version ...", flush=True)
    warmup = [run_cli([str(launcher), "version"], env, args.workdir)["ms"] for _ in range(3)]
    versions = [run_cli([str(launcher), "version"], env, args.workdir) for _ in range(args.version_runs)]
    report["cli_version"] = {
        "warmupMs": warmup,
        "wallMs": summarize([v["ms"] for v in versions]),
        "peakRssMiB": summarize([v["peakRssMiB"] for v in versions]),
        "failures": sum(1 for v in versions if v["exit"] != 0),
    }

    print(f"cli_run x{args.runs} ...", flush=True)
    cli_state = layout["cli"]
    cli_runs = [run_cli(cli_run_argv(launcher, cli_state, hello), env, args.workdir) for _ in range(args.runs)]
    report["cli_run"] = {
        "firstMs": cli_runs[0]["ms"],
        "firstNote": "first of the series: empty store and an empty script cache",
        "restWallMs": summarize([r["ms"] for r in cli_runs[1:]]),
        "totalMs": round(sum(r["ms"] for r in cli_runs), 1),
        "peakRssMiB": summarize([r["peakRssMiB"] for r in cli_runs]),
        "failures": sum(1 for r in cli_runs if r["exit"] != 0),
    }

    print("seeding the service store ...", flush=True)
    state = layout["state"]
    seed = run_cli(cli_run_argv(launcher, state, hello), env, args.workdir)
    if seed["exit"] != 0:
        sys.exit("bench-service-cost: the seeding run failed; the service has no store to read")

    sockets = layout["sock"] / "service.sock"
    serve_log = args.workdir / "serve.log"
    serve_argv = [
        str(launcher), "serve",
        "--db", str(state / "pipelinek.db"),
        "--control-root", str(state / "ctl"),
        "--socket", str(sockets),
        "--authorization-file", str(layout["auth"]),
        "--scripts", str(layout["scripts"]),
        "--max-supervised-runs", str(args.concurrency),
    ]
    print("service_startup ...", flush=True)
    started = time.perf_counter_ns()
    with open(serve_log, "wb") as log:
        service = subprocess.Popen(serve_argv, stdout=log, stderr=log, env=env, start_new_session=True)
    client = Client(sockets, token)
    if not wait_for_socket_answer(client, service):
        service.kill()
        sys.exit("bench-service-cost: the service did not answer; see serve.log in the work directory")
    startup_ms = ms_since(started)
    pid = service.pid
    jvm = Path(f"/proc/{pid}/exe").resolve()
    jvm_banner = command_output([str(jvm), "-version"]).splitlines()
    report["service_startup"] = {
        "msToFirstAnswer": round(startup_ms, 2),
        "launcherIsTheJvm": jvm.name == "java",
        "jvm": str(jvm),
        "jvmVersion": jvm_banner[1] if len(jvm_banner) > 1 else "unknown",
    }
    report["resources"] = {"idle": process_state(pid)}

    try:
        print("service_queries ...", flush=True)
        for _ in range(20):
            client.call("version")
        queries = {
            "version": summarize(query_series(client, "version", args.queries)),
            "health": summarize(query_series(client, "health", args.queries)),
            "runStateUnknownRun": summarize(query_series(client, "run.state", args.queries, runId="no-such-run")),
        }
        queries["concurrent"] = concurrent_queries(client, "health", args.queries, args.concurrency)
        report["service_queries"] = queries
        report["resources"]["afterQueries"] = process_state(pid)

        print(f"service_runs x{args.runs} sequential ...", flush=True)
        sequential = []
        samples = []
        for n in range(args.runs):
            sequential.append(start_and_wait(client, "hello.pipeline.kts", f"seq-{n}"))
            if (n + 1) % SAMPLE_EVERY == 0:
                samples.append({"afterRuns": n + 1, **process_state(pid)})
        report["resources"]["duringSequentialRuns"] = samples
        good = [r for r in sequential if r["ok"]]
        print(f"service_runs batch of {args.batch_runs} with {args.concurrency} in flight ...", flush=True)
        batch_start = time.perf_counter_ns()
        with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
            batch = list(pool.map(lambda n: start_and_wait(client, "hello.pipeline.kts", f"batch-{n}"),
                                  range(args.batch_runs)))
        batch_wall = ms_since(batch_start)
        batch_good = [r for r in batch if r["ok"]]
        report["service_runs"] = {
            "sequential": {
                "requested": len(sequential), "succeeded": len(good),
                "failureReasons": sorted({str(r.get("reason", r.get("outcome"))) for r in sequential if not r["ok"]}),
                "admissionMs": summarize([r["admissionMs"] for r in good]),
                "startToTerminalMs": summarize([r["totalMs"] for r in good]),
                "totalMs": round(sum(r["totalMs"] for r in sequential if "totalMs" in r), 1),
                "busyRetries": sum(r["busyRetries"] for r in sequential),
            },
            "batch": {
                "requested": len(batch), "succeeded": len(batch_good), "inFlight": args.concurrency,
                "failureReasons": sorted({str(r.get("reason", r.get("outcome"))) for r in batch if not r["ok"]}),
                "admissionMs": summarize([r["admissionMs"] for r in batch_good]),
                "startToTerminalMs": summarize([r["totalMs"] for r in batch_good]),
                "busyRetries": sum(r["busyRetries"] for r in batch),
                "wallMs": round(batch_wall, 1),
                "runsPerSecond": round(len(batch_good) / (batch_wall / 1000.0), 3),
            },
        }
        time.sleep(1.0)
        report["resources"]["afterRuns"] = process_state(pid)

        probe_run = good[0]["runId"] if good else None
        if probe_run:
            print("cli_inspect ...", flush=True)
            inspect_direct = [
                "inspect", probe_run, "--state",
                "--db", str(state / "pipelinek.db"), "--control-root", str(state / "ctl"),
            ]
            inspect_endpoint = [
                "inspect", probe_run, "--state",
                "--endpoint", str(sockets), "--authorization-file", str(layout["auth"]),
            ]
            for label, tail in (("cli_inspect_direct", inspect_direct), ("cli_inspect_endpoint", inspect_endpoint)):
                series = [run_cli([str(launcher), *tail], env, args.workdir) for _ in range(args.version_runs)]
                report[label] = {
                    "wallMs": summarize([s["ms"] for s in series]),
                    "failures": sum(1 for s in series if s["exit"] != 0),
                }
    finally:
        print("stopping the service ...", flush=True)
        before = children_of(pid)
        started_stop = time.perf_counter_ns()
        service.send_signal(signal.SIGTERM)
        try:
            service.wait(timeout=STOP_DEADLINE_S)
            stopped = True
        except subprocess.TimeoutExpired:
            service.kill()
            service.wait()
            stopped = False
        stop_ms = ms_since(started_stop)
        time.sleep(0.5)
        report["teardown"] = {
            "gracefulOnSigterm": stopped,
            "stopMs": round(stop_ms, 2),
            "exitCode": service.returncode,
            "socketFileLeft": sockets.exists(),
            "childrenBeforeStop": len(before),
            "childrenAlive": sum(1 for c in before if Path(f"/proc/{c}").exists()),
        }

    report["environment"]["loadAverageAtEnd"] = [round(v, 2) for v in os.getloadavg()]
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k != "environment"}, indent=2))
    shutil.copyfile(serve_log, args.out.with_suffix(".serve.log"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
