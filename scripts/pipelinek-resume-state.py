#!/usr/bin/env python3
"""Portable fail-closed recovery state for offline PipelineK runs.

Private per-session manifests are only an INDEX to the authoritative PipelineK
journal, not a replacement for it. No credentials or commands are persisted.
"""
from __future__ import annotations

from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

try:
    import fcntl
except ImportError:  # POSIX durable workflow locks are required for this adapter.
    fcntl = None

SCHEMA = "pipelinek.local-session/v1"
RUN_ID = re.compile(r"^[A-Za-z0-9_.-]{1,128}$")
MAX_GIT_BYTES = 128 * 1024 * 1024


def sha(value):
    return hashlib.sha256(value).hexdigest()


def checked_git(root, *args):
    try:
        completed = subprocess.run(["git", "-C", str(root), *args],
                                   stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                   timeout=30, check=False)
    except (FileNotFoundError, subprocess.TimeoutExpired):
        return None
    return completed.stdout if completed.returncode == 0 else None


def source_identity(project, script):
    """Fingerprint Git-visible inputs; explicitly flag unobserved ignored inputs."""
    script_bytes = script.read_bytes()
    script_sha = sha(script_bytes)
    top = checked_git(project, "rev-parse", "--show-toplevel")
    if not top or Path(os.fsdecode(top.strip())).resolve() != project.resolve():
        return {"mode": "script-only", "scriptSha256": script_sha}
    head = checked_git(project, "rev-parse", "HEAD")
    delta = checked_git(project, "diff", "--binary", "--no-ext-diff", "HEAD", "--")
    untracked = checked_git(project, "ls-files", "-z", "--others", "--exclude-standard")
    if head is None or delta is None or untracked is None:
        raise ValueError("cannot measure Git-visible workspace inputs")
    digest = hashlib.sha256(b"pipelinek.git-visible/v1\0" + head + delta)
    for raw in sorted(p for p in untracked.split(b"\0") if p):
        rel = os.fsdecode(raw)
        target = project / rel
        digest.update(b"\0path\0" + raw)
        if target.is_symlink():
            digest.update(b"\0symlink\0" + os.fsencode(os.readlink(target)))
        elif target.is_file():
            if target.stat().st_size > MAX_GIT_BYTES:
                raise ValueError("untracked input exceeds fingerprint limit: " + rel)
            with target.open("rb") as source:
                for chunk in iter(lambda: source.read(1024 * 1024), b""):
                    digest.update(chunk)
        else:
            raise ValueError("cannot fingerprint untracked input: " + rel)
    return {"mode": "git-visible", "scriptSha256": script_sha,
            "workspaceSha256": digest.hexdigest(),
            "caveat": "ignored files, environment, external systems and dependencies are not fingerprinted"}


def executable_identity(binary):
    """Use PipelineK's installed build identity, without exposing its stderr."""
    try:
        response = subprocess.run([str(binary), "version", "--json"],
                                  stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                  timeout=20, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise ValueError("cannot obtain installed PipelineK version identity")
    if response.returncode:
        raise ValueError("pipelinek version --json failed; refusing unverifiable resume")
    try:
        doc = json.loads(response.stdout)
    except (ValueError, UnicodeError):
        raise ValueError("invalid PipelineK version JSON")
    if not isinstance(doc, dict):
        raise ValueError("PipelineK version identity must be a JSON object")
    # Keep a digest, not arbitrary version output, in the private manifest.
    return sha(json.dumps(doc, sort_keys=True, separators=(",", ":")).encode())


def session_identity(project, script, workflow, binary_digest, extra, source):
    return {
        "project": sha(str(project.resolve()).encode()),
        "script": str(script.relative_to(project)),
        "workflow": workflow,
        "runtime": binary_digest,
        "invocation": sha(json.dumps(extra, ensure_ascii=False, separators=(",", ":")).encode()),
        "source": source,
    }


@contextmanager
def exclusive(history, workflow):
    """An OS file lock, not a PID/TTL guess: released automatically on crash."""
    if fcntl is None:
        raise ValueError("local recovery requires POSIX advisory file locks")
    root = history / "locks"
    root.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(root, 0o700)
    path = root / (sha(workflow.encode())[:20] + ".lock")
    descriptor = os.open(path, os.O_CREAT | os.O_RDWR, 0o600)
    try:
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError("another local runner already owns this workflow")
        yield
    finally:
        os.close(descriptor)


def new_manifest(identity, execution_id):
    return {
        "schema": SCHEMA, "executionId": execution_id, "identity": identity,
        "status": "PREPARED", "runId": None, "attempts": 0,
        "createdAt": datetime.now(timezone.utc).isoformat(),
    }


def pending(history, identity, session=None):
    """Select exactly one unfinished session; ambiguity is a refusal."""
    root = history / "executions"
    if session is not None and not RUN_ID.fullmatch(session):
        raise ValueError("invalid session identifier")
    candidates = []
    for item in sorted(root.glob("*/session.json")):
        if session and item.parent.name != session:
            continue
        if not item.is_file() or item.is_symlink():
            continue
        try:
            value = json.loads(item.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if value.get("schema") != SCHEMA:
            continue
        stored = value.get("identity")
        if not isinstance(stored, dict):
            continue
        # The workflow and script locate candidates; exact identity is checked
        # AFTER selecting, so a changed file is a visible refusal, never a new run.
        if stored.get("project") != identity["project"] or stored.get("workflow") != identity["workflow"] or stored.get("script") != identity["script"]:
            continue
        if value.get("status") not in ("TERMINAL", "ABANDONED"):
            candidates.append((item.parent, value))
    if len(candidates) != 1:
        raise ValueError("expected exactly one unfinished session; found " + str(len(candidates)) +
                         "; specify --session if multiple, or start a new run explicitly")
    folder, manifest = candidates[0]
    if manifest["identity"] != identity:
        raise ValueError("resume refused: source, installed PipelineK, or options changed since the run started")
    return folder, manifest


def prior_run_id(folder, manifest):
    """Recover launch ID from PipelineK's own atomic durable last-run record."""
    known = manifest.get("runId")
    if isinstance(known, str) and RUN_ID.fullmatch(known):
        return known
    records = [p for p in (folder / "control" / "last-run").glob("*") if p.is_file() and not p.is_symlink()]
    if len(records) != 1:
        raise ValueError("launch receipt missing and durable last-run mapping is ambiguous or absent")
    raw = records[0].read_text(encoding="utf-8").splitlines()
    value = raw[0].strip() if raw else ""
    if not RUN_ID.fullmatch(value):
        raise ValueError("corrupt durable last-run ID")
    return value


def run_state(binary, folder, run_id):
    """Read-only durable state; it cannot decide an external side effect."""

    command = [str(binary), "inspect", run_id, "--state",
               "--db", str(folder / "journal.db"),
               "--control-root", str(folder / "control")]
    try:
        result = subprocess.run(command, stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, timeout=30, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise ValueError("cannot inspect the previous durable run")
    if result.returncode:
        raise ValueError("inspect --state refused the previous run; manual diagnosis required")
    line = result.stdout.decode("utf-8", "replace").strip()
    if not (line.startswith("run-state terminal ") or
            line in ("run-state running", "run-state empty", "run-state unobservable")):
        raise ValueError("unexpected run-state: refuse automatic resume")
    return line


def preflight(binary, folder, run_id):
    """Only an incomplete run is eligible for PipelineK's native --resume."""
    line = run_state(binary, folder, run_id)
    if line.startswith("run-state terminal "):
        raise ValueError("run has a terminal outcome; starting --resume would not be crash recovery")
    return line


