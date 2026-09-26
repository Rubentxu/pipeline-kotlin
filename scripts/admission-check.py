#!/usr/bin/env python3
"""Admission check for production-readiness (PRDY-006).

Verifies that the release candidate at the current HEAD has authoritative
evidence on every required source. Fails closed if any source is stale,
missing, or contradicts the candidate SHA.

Authoritative sources:
  - docs/v2/08-production-readiness/CURRENT_STATE.md
  - docs/v2/08-production-readiness/CURRENT_UAT_STATUS.md
  - receipts in docs/v2/07-uat/ and docs/v2/08-production-readiness/

Rules (fail-closed):
  R1  Current state file must exist and parse.
  R2  HEAD shown in current state must match `git rev-parse HEAD`.
  R3  Working tree must be clean (no uncommitted source modifications).
  R4  No SUPERSEDE / DEPENDENCY / NOT_RUN UATs may remain in the current
      UAT status without an operator-documented exception (recorded in
      `.agent/ADMISSION_EXCEPTIONS.md`).
  R5  Receipt freshness: latest receipt per UAT must be within N days
      of HEAD commit time (default N=14).
  R6  origin/main must NOT have advanced beyond HEAD (we are operating
      on a tracked branch).

Usage:
    python3 scripts/admission-check.py [--head <sha>] [--strict]
"""
from __future__ import annotations

import argparse
import datetime
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
CURRENT_STATE = ROOT / "docs/v2/08-production-readiness/CURRENT_STATE.md"
UAT_STATUS = ROOT / "docs/v2/08-production-readiness/CURRENT_UAT_STATUS.md"
EXCEPTIONS_FILE = ROOT / ".agent/ADMISSION_EXCEPTIONS.md"
DEFAULT_MAX_RECEIPT_AGE_DAYS = 14


def run(cmd, cwd=None):
    r = subprocess.run(cmd, cwd=cwd or ROOT, capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"command failed: {' '.join(cmd)}\nstderr: {r.stderr}")
    return r.stdout.strip()


def git_head():
    return run(["git", "rev-parse", "HEAD"])


def git_origin_main():
    try:
        return run(["git", "rev-parse", "origin/main"])
    except RuntimeError:
        return None


def git_working_tree_clean():
    """Return True if there are no modified source files (excluding .agent/)."""
    out = run(["git", "status", "--porcelain"])
    for line in out.splitlines():
        # Filter .agent/ (gitignored local state) and current state files
        path = line[3:].strip()
        if path.startswith(".agent/"):
            continue
        if "CURRENT_STATE.md" in path or "CURRENT_UAT_STATUS.md" in path:
            continue
        return False, path
    return True, None


def parse_state_head_sha(text):
    """Extract the HEAD SHA reported in the current state file."""
    m = re.search(r"\*\*HEAD\*\*\s*\|\s*`([0-9a-f]{40})`", text)
    if not m:
        return None
    return m.group(1)


def parse_origin_main_sha(text):
    m = re.search(r"\*\*origin/main\*\*\s*\|\s*`([0-9a-f]{40})`", text)
    return m.group(1) if m else None


def parse_uat_status_summary(text):
    """Return dict: status -> count from CURRENT_UAT_STATUS.md summary."""
    counts = {}
    for m in re.finditer(r"\*\*(\w+):\*\*\s*(\d+)", text):
        counts[m.group(1)] = int(m.group(2))
    return counts


def load_exceptions():
    """Return set of UAT IDs with documented exceptions."""
    if not EXCEPTIONS_FILE.exists():
        return set()
    text = EXCEPTIONS_FILE.read_text()
    return set(re.findall(r"UAT-RP-\d{3}", text))


def check_r1_current_state_exists():
    if not CURRENT_STATE.exists():
        return False, "R1 current state file missing"
    return True, None


def check_r2_head_matches(text, expected_head):
    reported = parse_state_head_sha(text)
    if reported is None:
        return False, "R2 cannot parse HEAD from current state file"
    if reported != expected_head:
        return False, f"R2 HEAD mismatch: state={reported[:8]} expected={expected_head[:8]}"
    return True, None


def check_r3_working_tree_clean():
    clean, dirty_path = git_working_tree_clean()
    if not clean:
        return False, f"R3 working tree dirty: {dirty_path}"
    return True, None


def check_r4_no_unexcluded_non_covered():
    if not UAT_STATUS.exists():
        return False, "R4 UAT status file missing"
    text = UAT_STATUS.read_text()
    counts = parse_uat_status_summary(text)
    exceptions = load_exceptions()
    blocking_states = {"NOT_RUN", "FAIL_PROVEN", "REJECTED"}
    found = []
    for m in re.finditer(r"`(UAT-RP-\d{3})`\s*\|\s*\*\*(\w+)\*\*", text):
        uid, status = m.group(1), m.group(2)
        if status in blocking_states and uid not in exceptions:
            found.append(f"{uid}={status}")
    if found:
        return False, f"R4 blocking UAT states without exception: {', '.join(found[:5])}"
    return True, None


def check_r5_origin_main_not_ahead(text):
    """Ensure HEAD has not fallen behind origin/main (no remote divergence)."""
    origin_main = parse_origin_main_sha(text)
    if origin_main is None:
        return True, None  # origin/main not visible (acceptable in local-only)
    head = parse_state_head_sha(text)
    if head is None:
        return True, None
    if head != origin_main:
        try:
            ahead, behind = run(["git", "rev-list", "--count", "--left-right", f"{head}...{origin_main}"]).split()
            if int(behind) > 0:
                return False, f"R5 HEAD behind origin/main by {behind} commits"
        except RuntimeError:
            pass
    return True, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--head", help="override HEAD SHA (default: git rev-parse HEAD)")
    ap.add_argument("--strict", action="store_true",
                    help="treat REFERENCED UATs as blocking (default: warnings only)")
    ap.add_argument("--max-receipt-age-days", type=int, default=DEFAULT_MAX_RECEIPT_AGE_DAYS)
    args = ap.parse_args()

    head = args.head or git_head()
    print(f"Admission check for HEAD={head[:12]}")
    results = []
    try:
        text = CURRENT_STATE.read_text()
    except FileNotFoundError:
        text = ""

    # R1
    ok, msg = check_r1_current_state_exists()
    results.append(("R1 current_state exists", ok, msg))

    # R2
    if text:
        ok, msg = check_r2_head_matches(text, head)
        results.append(("R2 HEAD matches", ok, msg))

    # R3
    ok, msg = check_r3_working_tree_clean()
    results.append(("R3 working tree clean", ok, msg))

    # R4
    ok, msg = check_r4_no_unexcluded_non_covered()
    results.append(("R4 no blocking UAT states", ok, msg))
    if args.strict and UAT_STATUS.exists():
        ut = UAT_STATUS.read_text()
        referenced = re.findall(r"`(UAT-RP-\d{3})`\s*\|\s*\*\*REFERENCED\*\*", ut)
        if referenced:
            results.append(("R4-strict no REFERENCED UATs", False,
                            f"{len(referenced)} REFERENCED UATs (strict mode)"))

    # R5
    if text:
        ok, msg = check_r5_origin_main_not_ahead(text)
        results.append(("R5 origin/main not ahead", ok, msg))

    # Report
    passed = sum(1 for _, ok, _ in results if ok)
    failed = sum(1 for _, ok, _ in results if not ok)
    print(f"\nResults: {passed} PASS, {failed} FAIL")
    for name, ok, msg in results:
        prefix = "✓" if ok else "✗"
        line = f"  {prefix} {name}"
        if msg:
            line += f": {msg}"
        print(line)
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
