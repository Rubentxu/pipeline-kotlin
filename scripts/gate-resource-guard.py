#!/usr/bin/env python3
"""Concurrency limit for the heavy gates (mandate B3.2).

Why
---
Measured on this host (2026-10-15): a sibling worktree running
`check --rerun-tasks` at the same time took a warm no-op task from a 2.4 s median
to 18.7 s max, killed the `:pipeline-events-store:test` executor with SIGKILL
(exit 137) and left another run without its Gradle daemon. The costs are not just
slower feedback: a contended run produces numbers and verdicts that describe the
machine, not the code, and a killed executor produces no evidence at all.

So the heavy gates refuse to start while another gate is already running on the
host. The refusal is typed and reversible:

    python3 scripts/gate-resource-guard.py --gate L-ROUND        # exit 0 or 2
    python3 scripts/gate-resource-guard.py --gate L-ROUND --force # always exit 0
    PIPELINEK_GATE_ALLOW_CONCURRENT=1 just gate                   # deliberate overlap

Refusal reasons are never silent: the offender's pid and command line are shown,
and the bypass is recorded in the caller's receipt (the recipes pass --force
through when the operator asks for it).

Detection is over `/proc` and hermetic in tests: `gate_processes()` takes the
proc root and the calling pid as arguments, so a test builds a fixture tree
instead of depending on this host.

Exit codes: 0 allowed (no other gate, or forced), 2 refused, 3 cannot inspect.
"""
from __future__ import annotations

import argparse
import os
import pathlib
import sys

PROC_ROOT = pathlib.Path("/proc")

# What counts as "a gate". Deliberately narrow: a warm Gradle daemon
# (GradleDaemon) is not a gate, and neither is a single targeted `:mod:test`.
GATE_PATTERNS = (
    "v2/gradlew",
    "./gradlew",
    "gradlew -p v2",
    ":pipeline-release:releaseGate",
    "run-integration-gate.py",
)


def _cmdline(pid_dir: pathlib.Path) -> str:
    try:
        raw = (pid_dir / "cmdline").read_bytes()
    except OSError:
        return ""
    return raw.decode("utf-8", "replace").replace("\x00", " ").strip()


def _parent_of(pid_dir: pathlib.Path) -> int | None:
    try:
        for line in (pid_dir / "status").read_text(errors="replace").splitlines():
            if line.startswith("PPid:"):
                return int(line.split()[1])
    except (OSError, ValueError, IndexError):
        return None
    return None


def _ancestors(proc_root: pathlib.Path, pid: int) -> set[int]:
    """pids from `pid` upwards, so the calling chain is never an offender.

    The guard is spawned by `just`, whose command line contains the recipe and
    therefore the same words as the gate it is running.
    """
    seen: set[int] = set()
    current = pid
    while current and current not in seen:
        seen.add(current)
        parent = _parent_of(proc_root / str(current))
        if parent is None or parent <= 1:
            break
        current = parent
    return seen


def gate_processes(proc_root: pathlib.Path = PROC_ROOT, *, self_pid: int | None = None,
                   patterns: tuple[str, ...] = GATE_PATTERNS) -> list[tuple[int, str]]:
    """Return (pid, cmdline) of gates running besides this process tree."""
    pid = os.getpid() if self_pid is None else self_pid
    ignored = _ancestors(proc_root, pid)
    found: list[tuple[int, str]] = []
    try:
        entries = sorted(proc_root.iterdir(), key=lambda p: p.name)
    except OSError:
        return found
    for entry in entries:
        if not entry.name.isdigit():
            continue
        other = int(entry.name)
        if other in ignored:
            continue
        cmdline = _cmdline(entry)
        if not cmdline:
            continue
        if any(pattern in cmdline for pattern in patterns):
            found.append((other, cmdline))
    return found


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--gate", required=True,
                    help="gate being started (L-ROUND, L-MERGE, L-RC, ...)")
    ap.add_argument("--force", action="store_true",
                    help="start anyway; the caller must record the bypass")
    ap.add_argument("--proc-root", default=str(PROC_ROOT),
                    help="process table root (tests use a fixture tree)")
    args = ap.parse_args(argv)

    if args.force or os.environ.get("PIPELINEK_GATE_ALLOW_CONCURRENT") == "1":
        print(f"gate-guard: {args.gate} starting with concurrency allowed "
              "(same-host limits bypassed; record this in the receipt)")
        return 0

    proc_root = pathlib.Path(args.proc_root)
    if not proc_root.is_dir():
        print(f"gate-guard: cannot inspect the process table at {proc_root}; "
              "refusing to start a heavy gate blind", file=sys.stderr)
        return 3

    offenders = gate_processes(proc_root)
    if not offenders:
        print(f"gate-guard: {args.gate} allowed (no other gate running)")
        return 0

    print(f"gate-guard: REFUSED to start {args.gate}: another gate is running on "
          "this host.", file=sys.stderr)
    for pid, cmdline in offenders:
        print(f"  pid={pid} {cmdline[:160]}", file=sys.stderr)
    print("  Concurrent heavy gates distort timings and can kill executors "
          "(measured 2026-10-15: SIGKILL 137, lost daemon).", file=sys.stderr)
    print("  Wait for it, or pass --force / PIPELINEK_GATE_ALLOW_CONCURRENT=1 "
          "and record the bypass.", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
