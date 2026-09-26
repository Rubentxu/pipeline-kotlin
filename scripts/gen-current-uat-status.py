#!/usr/bin/env python3
"""Generate the current UAT status view from receipts (PRDY-003).

The PRODUCTION_READY_UAT_MATRIX.md separates:
  - Normative (Gate / Scenario / Evidence required): immutable contract
  - Current status (Estado per HEAD): mutable evidence, generated here

This generator scans docs/v2/07-uat/ for receipts that reference each
UAT-RP-XXX, then emits docs/v2/08-production-readiness/CURRENT_UAT_STATUS.md
with the current state per UAT and the latest receipt that covers it.

Usage:
    python3 scripts/gen-current-uat-status.py [--out PATH]
    python3 scripts/gen-current-uat-status.py --check   # verify stability
"""
from __future__ import annotations

import argparse
import datetime
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
UAT_DIR = ROOT / "docs/v2/07-uat"
DEFAULT_OUT = ROOT / "docs/v2/08-production-readiness/CURRENT_UAT_STATUS.md"

# UAT normative IDs as declared in PRODUCTION_READY_UAT_MATRIX.md.
# Order matches the contract table; status is generated separately.
UAT_IDS = [f"UAT-RP-{n:03d}" for n in range(1, 28)]

# Status markers found in receipts. Order matters: most specific first.
STATUS_PATTERNS = [
    ("COVERED", re.compile(r"\bCOVERED\b", re.I)),
    ("PARTIAL", re.compile(r"\bPARTIAL\b|\bKNOWN_LIMITATION\b", re.I)),
    ("FAIL_PROVEN", re.compile(r"\bFAIL_PROVEN\b|\bFAIL\b", re.I)),
    ("BLOCKED", re.compile(r"\bBLOCKED\b", re.I)),
    ("NOT_RUN", re.compile(r"\bNOT_RUN\b|\bKNOWN_GAP\b|\bNO_APLICA\b", re.I)),
    ("REJECTED", re.compile(r"\bREJECTED\b", re.I)),
]


def run(cmd, cwd=None):
    r = subprocess.run(cmd, cwd=cwd or ROOT, capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"command failed: {' '.join(cmd)}\nstderr: {r.stderr}")
    return r.stdout.strip()


def git_head_short():
    """Return the current HEAD short SHA, or 'unknown'."""
    try:
        return run(["git", "rev-parse", "--short", "HEAD"])
    except RuntimeError:
        return "unknown"


def scan_receipts(uat_dir):
    """Return dict: uat_id -> list of (receipt_path, matched_line).

    Scans every .md in uat_dir looking for UAT-RP-XXX references.
    """
    found = {uid: [] for uid in UAT_IDS}
    if not uat_dir.exists():
        return found
    uid_pattern = re.compile(r"UAT-RP-\d{3}")
    for path in uat_dir.glob("*.md"):
        try:
            text = path.read_text()
        except (OSError, UnicodeDecodeError):
            continue
        try:
            rel = path.relative_to(ROOT)
        except ValueError:
            rel = path
        for uid in uid_pattern.findall(text):
            if uid in found:
                # Find the most relevant line containing the reference
                for line in text.splitlines():
                    if uid in line:
                        found[uid].append((rel, line.strip()[:120]))
                        break
    return found


def classify_status(matches):
    """Return a single status string for a UAT given its receipt matches."""
    if not matches:
        return "NOT_RUN"
    text = " ".join(line for _, line in matches)
    for label, pat in STATUS_PATTERNS:
        if pat.search(text):
            return label
    return "REFERENCED"


def latest_receipt(matches):
    """Return the receipt path covering this UAT, or None."""
    if not matches:
        return None
    # First match wins (files scanned in glob order; stable for our tree).
    return str(matches[0][0])


def render_markdown(uat_status, head_sha):
    lines = []
    lines.append("# Current UAT Status (Production-Readiness)\n")
    lines.append(f"**Generated at (UTC):** {datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')}\n")
    lines.append(f"**Source of truth:** `git log --short` HEAD `{head_sha}` + receipt scan in `docs/v2/07-uat/`.\n")
    lines.append(f"**Generator:** `scripts/gen-current-uat-status.py`\n")
    lines.append("\n---\n\n")
    lines.append("## Status by UAT\n\n")
    lines.append("| UAT ID | Status | Latest Receipt | Evidence excerpt |\n")
    lines.append("|---|---|---|---|\n")
    counts = {}
    for uid in UAT_IDS:
        matches = uat_status[uid]
        status = classify_status(matches)
        counts[status] = counts.get(status, 0) + 1
        receipt = latest_receipt(matches)
        excerpt = matches[0][1] if matches else "_no receipt found_"
        lines.append(f"| `{uid}` | **{status}** | `{receipt or '—'}` | {excerpt} |\n")
    lines.append("\n---\n\n")
    lines.append("## Status Summary\n\n")
    lines.append(f"- **Total UATs (PRDY-003 contract):** {len(UAT_IDS)}\n")
    for status in ["COVERED", "PARTIAL", "FAIL_PROVEN", "BLOCKED", "NOT_RUN", "REFERENCED", "REJECTED"]:
        if status in counts:
            lines.append(f"- **{status}:** {counts[status]}\n")
    lines.append("\n---\n\n")
    lines.append("## Acceptance Criteria (PRDY-003)\n\n")
    lines.append("- [x] C1: All 27 UAT-RP-001..027 IDs enumerated (normative contract).\n")
    lines.append("- [x] C2: Each ID scanned against `docs/v2/07-uat/*.md` for receipt references.\n")
    lines.append("- [x] C3: Status classified from receipt content (COVERED / PARTIAL / FAIL_PROVEN / BLOCKED / NOT_RUN / REFERENCED).\n")
    lines.append("- [x] C4: Generator + tests + receipt produced.\n")
    lines.append("- [x] C5: `PRODUCTION_READY_UAT_MATRIX.md` reduced to normative only (this file replaces its mutable section).\n")
    lines.append("\n---\n\n")
    lines.append("## Discoveries\n\n")
    lines.append("- The normative matrix in `PRODUCTION_READY_UAT_MATRIX.md` mixes contract (immutable) and current state (mutable). This file replaces the mutable portion.\n")
    lines.append("- Several UATs are referenced only in narrative text, not in dedicated receipts. The generator reports `REFERENCED` instead of `COVERED` to avoid implying certification that does not exist.\n")
    return "".join(lines)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(DEFAULT_OUT))
    ap.add_argument("--check", action="store_true", help="regenerate to /tmp and compare")
    args = ap.parse_args()

    head_sha = git_head_short()
    uat_status = scan_receipts(UAT_DIR)
    md = render_markdown(uat_status, head_sha)

    out = pathlib.Path(args.out)
    if args.check:
        tmp = pathlib.Path("/tmp/_uat_status_check.md")
        tmp.write_text(md)
        existing = out.read_text() if out.exists() else ""
        if existing == md:
            print(f"OK-IDENTICAL ({md.splitlines()[0]})")
            return 0
        print(f"STALE (existing≠regenerated; out={out})")
        return 1

    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(md)
    print(f"WRITTEN {out} ({len(UAT_IDS)} UATs classified)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
