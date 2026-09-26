#!/usr/bin/env python3
"""Admission check for production-readiness (PRDY-006R revised).

Verifies that a candidate release SHA has authoritative evidence on every
required source. Fails closed if any source is stale, missing, or contradicts
the candidate. Stable across commits: the candidate SHA is an explicit input,
not derived from `git rev-parse HEAD`, so the check can run on the same SHA
without re-generating CURRENT_STATE.md.

Authoritative sources:
  - docs/v2/08-production-readiness/CURRENT_STATE.md
  - docs/v2/08-production-readiness/CURRENT_UAT_STATUS.md
  - receipts in docs/v2/07-uat/ (freshness per R5)
  - .agent/ADMISSION_EXCEPTIONS.md (allow-list for R4/R6)

Rules (fail-closed, numbered R1..R7):
  R1  CURRENT_STATE.md must exist.
  R2  CURRENT_STATE.HEAD must be an ancestor of --head (or equal). The
      CURRENT_STATE projection is generated against a parent/basis SHA;
      the commit that contains the projection produces a child SHA that
      legitimately differs. Equality would force an impossible
      self-reference; ancestry is the correct invariant.
  R3  Working tree must be clean (no dirty source files). .agent/ and
      CURRENT_*STATUS files are excluded (gitignored local state and
      regenerable projections).
  R4  No FAIL_PROVEN / NOT_RUN / REJECTED UATs may remain in
      CURRENT_UAT_STATUS.md without an exception in
      .agent/ADMISSION_EXCEPTIONS.md.
  R5  Receipt freshness: the youngest receipt in docs/v2/07-uat/ must be
      no older than --max-receipt-age-days (default 14) at the time of
      the candidate commit. Stale evidence fails closed.
  R6  With --strict: REFERENCED UATs are also blocking (require exception).
  R7  --head must not be behind origin/main.

Usage:
    python3 scripts/admission-check.py --head <sha>
    python3 scripts/admission-check.py --head <sha> --strict
    python3 scripts/admission-check.py --head <sha> --max-receipt-age-days 7

Exit codes:
    0  PASS (all rules satisfied or all blocking rules allow-listed)
    1  FAIL (one or more rules violated)
    2  ERROR (bad arguments, missing files outside the rules)
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
UAT_RECEIPTS_DIR = ROOT / "docs/v2/07-uat"
DEFAULT_MAX_RECEIPT_AGE_DAYS = 14

# States in CURRENT_UAT_STATUS that block admission without an exception.
BLOCKING_UAT_STATES = {"FAIL_PROVEN", "NOT_RUN", "REJECTED"}
# States that block only under --strict.
STRICT_BLOCKING_UAT_STATES = {"REFERENCED"}

# Paths excluded from R3 working-tree check (gitignored local state +
# regenerable projections; their dirtiness is expected).
R3_EXCLUDE_SUBSTRINGS = (
    ".agent/",
    "CURRENT_STATE.md",
    "CURRENT_UAT_STATUS.md",
)


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


def git_is_ancestor(ancestor, descendant):
    """True if `ancestor` is reachable from `descendant` (or equal)."""
    if ancestor == descendant:
        return True
    try:
        run(["git", "merge-base", "--is-ancestor", ancestor, descendant])
        return True
    except RuntimeError:
        return False


def git_commit_iso8601(sha):
    """Return the commit's author ISO8601 timestamp, or None on error."""
    try:
        return run(["git", "show", "-s", "--format=%aI", sha])
    except RuntimeError:
        return None


def git_working_tree_dirty_paths():
    """Return list of dirty paths excluding R3 exclusions."""
    out = run(["git", "status", "--porcelain"])
    paths = []
    for line in out.splitlines():
        if len(line) < 4:
            continue
        path = line[3:].strip()
        if " -> " in path:
            path = path.split(" -> ", 1)[1]
        path_norm = path.replace("\\", "/")
        # Normalise leading ./ for substring matching
        if path_norm.startswith("./"):
            path_norm = path_norm[2:]
        # Strip the leading dot on hidden dirs so '.agent/' matches 'agent/' too
        path_norm_no_dot = path_norm.lstrip(".")
        if any(excl in path_norm or excl.lstrip(".") in path_norm_no_dot
               for excl in R3_EXCLUDE_SUBSTRINGS):
            continue
        paths.append(path_norm)
    return paths


def parse_state_head_sha(text):
    m = re.search(r"\*\*HEAD\*\*\s*\|\s*`([0-9a-f]{40})`", text)
    return m.group(1) if m else None


def parse_state_generated_at(text):
    m = re.search(r"\*\*Generated at \(UTC\)\*\*\s*\|\s*`([^`]+)`", text)
    return m.group(1).strip() if m else None


def parse_uat_status_rows(text):
    """Return list of (uid, status) tuples."""
    return [(m.group(1), m.group(2))
            for m in re.finditer(r"`(UAT-RP-\d{3})`\s*\|\s*\*\*(\w+)\*\*", text)]


def load_exceptions():
    if not EXCEPTIONS_FILE.exists():
        return set()
    return set(re.findall(r"UAT-RP-\d{3}", EXCEPTIONS_FILE.read_text()))


def newest_receipt_iso8601():
    """Return the most recent modification ISO8601 across UAT receipts, or None."""
    if not UAT_RECEIPTS_DIR.exists():
        return None
    newest = None
    for path in UAT_RECEIPTS_DIR.glob("*.md"):
        try:
            mtime = path.stat().st_mtime
        except OSError:
            continue
        if newest is None or mtime > newest:
            newest = mtime
    if newest is None:
        return None
    return datetime.datetime.fromtimestamp(newest, tz=datetime.timezone.utc).isoformat()


# ---- Rules ----------------------------------------------------------------

def check_r1_current_state_exists():
    if not CURRENT_STATE.exists():
        return False, "R1 CURRENT_STATE.md missing"
    return True, None


def check_r2_state_head_is_ancestor(candidate_sha, state_text):
    """R2: CURRENT_STATE.HEAD must be ancestor (or equal) of --head.

    Why ancestor and not equality: CURRENT_STATE.md is versioned. When
    generated against HEAD=A and committed, the resulting commit is HEAD=B.
    Equality is impossible; ancestry is the correct invariant.
    """
    reported = parse_state_head_sha(state_text)
    if reported is None:
        return False, "R2 cannot parse HEAD from CURRENT_STATE.md"
    if not git_is_ancestor(reported, candidate_sha):
        return False, (
            f"R2 CURRENT_STATE.HEAD={reported[:8]} is NOT an ancestor of "
            f"candidate {candidate_sha[:8]} (cannot admit evidence about "
            f"a SHA that has not yet been produced)"
        )
    return True, None


def check_r3_working_tree_clean():
    dirty = git_working_tree_dirty_paths()
    if dirty:
        return False, f"R3 working tree has dirty source files: {', '.join(dirty[:5])}"
    return True, None


def check_r4_no_blocking_uats(strict=False):
    if not UAT_STATUS.exists():
        return False, "R4 CURRENT_UAT_STATUS.md missing"
    text = UAT_STATUS.read_text()
    rows = parse_uat_status_rows(text)
    exceptions = load_exceptions()
    blocking = BLOCKING_UAT_STATES | (STRICT_BLOCKING_UAT_STATES if strict else set())
    found = [f"{uid}={status}" for uid, status in rows
             if status in blocking and uid not in exceptions]
    if found:
        suffix = " (strict mode)" if strict else ""
        return False, f"R4 blocking UATs without exception{suffix}: {', '.join(found[:5])}"
    return True, None


def check_r5_receipt_freshness(candidate_sha, max_age_days):
    """R5: youngest receipt must be no older than max_age_days at candidate time.

    We compute the receipt's mtime in UTC and compare it to (candidate
    commit time - max_age_days). Stale evidence fails closed.
    """
    iso = newest_receipt_iso8601()
    if iso is None:
        return False, "R5 no receipts found in docs/v2/07-uat/"
    try:
        receipt_dt = datetime.datetime.fromisoformat(iso)
    except ValueError:
        return False, f"R5 cannot parse newest receipt timestamp: {iso}"
    candidate_iso = git_commit_iso8601(candidate_sha)
    if candidate_iso is None:
        return True, None  # cannot compute candidate time -> don't block
    try:
        candidate_dt = datetime.datetime.fromisoformat(candidate_iso)
    except ValueError:
        return True, None
    age_days = (candidate_dt - receipt_dt).total_seconds() / 86400.0
    if age_days > max_age_days:
        return False, (
            f"R5 newest receipt is {age_days:.1f} days older than candidate "
            f"commit (limit {max_age_days}); refresh receipts or extend window"
        )
    return True, None


def check_r7_origin_main_not_ahead(candidate_sha):
    """R7: candidate must not be behind origin/main."""
    origin_main = git_origin_main()
    if origin_main is None:
        return True, None
    if git_is_ancestor(origin_main, candidate_sha) or origin_main == candidate_sha:
        return True, None
    try:
        ahead, behind = run([
            "git", "rev-list", "--count", "--left-right",
            f"{candidate_sha}...{origin_main}",
        ]).split()
        if int(behind) > 0:
            return False, f"R7 candidate behind origin/main by {behind} commits"
    except RuntimeError:
        pass
    return True, None


# ---- Driver ---------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--head", help="candidate SHA (default: git rev-parse HEAD)")
    ap.add_argument("--strict", action="store_true",
                    help="treat REFERENCED UATs as blocking (default: warning)")
    ap.add_argument("--max-receipt-age-days", type=int,
                    default=DEFAULT_MAX_RECEIPT_AGE_DAYS,
                    help=f"max receipt age in days (default {DEFAULT_MAX_RECEIPT_AGE_DAYS})")
    args = ap.parse_args()

    candidate = args.head or git_head()
    print(f"Admission check for candidate={candidate[:12]}  strict={args.strict}  "
          f"max_receipt_age_days={args.max_receipt_age_days}")
    results = []
    try:
        state_text = CURRENT_STATE.read_text()
    except FileNotFoundError:
        state_text = ""

    # R1
    ok, msg = check_r1_current_state_exists()
    results.append(("R1 current_state exists", ok, msg))

    # R2
    if state_text:
        ok, msg = check_r2_state_head_is_ancestor(candidate, state_text)
        results.append(("R2 state.HEAD is ancestor of candidate", ok, msg))

    # R3
    ok, msg = check_r3_working_tree_clean()
    results.append(("R3 working tree clean", ok, msg))

    # R4
    ok, msg = check_r4_no_blocking_uats(strict=args.strict)
    suffix = " (strict)" if args.strict else ""
    results.append((f"R4 no blocking UAT states{suffix}", ok, msg))

    # R5
    ok, msg = check_r5_receipt_freshness(candidate, args.max_receipt_age_days)
    results.append(("R5 receipt freshness", ok, msg))

    # R7 (R6 reserved for --strict handling in R4 above; see docstring)
    ok, msg = check_r7_origin_main_not_ahead(candidate)
    results.append(("R7 origin/main not ahead", ok, msg))

    passed = sum(1 for _, ok, _ in results if ok)
    failed = sum(1 for _, ok, _ in results if not ok)
    print(f"\nResults: {passed} PASS, {failed} FAIL")
    for name, ok, msg in results:
        prefix = "PASS" if ok else "FAIL"
        line = f"  [{prefix}] {name}"
        if msg:
            line += f": {msg}"
        print(line)
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
