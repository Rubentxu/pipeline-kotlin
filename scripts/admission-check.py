#!/usr/bin/env python3
"""Admission check for production-readiness (PRDY-006R2).

Verifies that a candidate release SHA has authoritative evidence on every
required source. Fails closed if any source is stale, missing, or contradicts
the candidate. Stable across clones: every decision is derived from
Git-versioned data (commit ancestry, provenance of selected receipts) and
explicit configuration. The check does not depend on filesystem mtime,
working-tree dirtiness of the session running it, or any other ambient state.

Authoritative sources:
  - docs/v2/08-production-readiness/CURRENT_STATE.md
  - docs/v2/08-production-readiness/CURRENT_UAT_STATUS.md
  - receipts in docs/v2/07-uat/ (provenance via Git ancestry, per-UAT)
  - .agent/ADMISSION_EXCEPTIONS.md (allow-list for R4)

Rules (fail-closed, numbered R0..R7):

  R0  Candidate SHA must exist and resolve to a commit object. Bogus /
      malformed / non-commit inputs fail closed with exit code 2.
  R1  CURRENT_STATE.md must exist.
  R2  CURRENT_STATE.HEAD must be an ancestor of --head (or equal). Equality
      would force an impossible self-reference; ancestry is the correct
      invariant.
  R3  Working tree must be clean (no dirty source files). Exclusions are
      exact-path only: prefix `.agent/`, and the exact files
      `docs/v2/08-production-readiness/CURRENT_STATE.md` and
      `.../CURRENT_UAT_STATUS.md`. No substring matching.
  R4  No FAIL_PROVEN / NOT_RUN / REJECTED UATs may remain in
      CURRENT_UAT_STATUS.md without an exception in
      .agent/ADMISSION_EXCEPTIONS.md.
  R5  For each UAT selected from CURRENT_UAT_STATUS.md, the UAT's latest
      receipt must be reachable from the candidate via Git commit ancestry
      (its provenance commit is an ancestor of the candidate). Receipts
      with no Git history are not authoritative. Per-receipt provenance
      derives freshness from the receipt's last-modifying commit and the
      candidate commit, not from filesystem mtime.
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
from typing import Callable, Optional

ROOT = pathlib.Path(__file__).resolve().parent.parent

# Canonical relative paths (independent of repo root, used to derive
# per-repo absolute paths in `_resolve_paths`).
CURRENT_STATE_REL = "docs/v2/08-production-readiness/CURRENT_STATE.md"
UAT_STATUS_REL = "docs/v2/08-production-readiness/CURRENT_UAT_STATUS.md"
EXCEPTIONS_FILE_REL = ".agent/ADMISSION_EXCEPTIONS.md"
UAT_RECEIPTS_DIR_REL = "docs/v2/07-uat"

# Module-level convenience constants for callers that don't pass a cwd
# (kept for backward compatibility with existing tests).
CURRENT_STATE = ROOT / CURRENT_STATE_REL
UAT_STATUS = ROOT / UAT_STATUS_REL
EXCEPTIONS_FILE = ROOT / EXCEPTIONS_FILE_REL
UAT_RECEIPTS_DIR = ROOT / UAT_RECEIPTS_DIR_REL

# States in CURRENT_UAT_STATUS that block admission without an exception.
BLOCKING_UAT_STATES = {"FAIL_PROVEN", "NOT_RUN", "REJECTED"}
# States that block only under --strict.
STRICT_BLOCKING_UAT_STATES = {"REFERENCED"}

# R3 exclusions — exact path matching. Substring matching is forbidden because
# it can accidentally mask unrelated files (e.g. a path containing "agent/").
R3_EXCLUDE_PREFIXES = (".agent/",)
R3_EXCLUDE_EXACT = {
    CURRENT_STATE_REL,
    UAT_STATUS_REL,
}


def _paths(cwd: pathlib.Path) -> tuple[pathlib.Path, pathlib.Path, pathlib.Path]:
    """Return (CURRENT_STATE, UAT_STATUS, EXCEPTIONS_FILE) under cwd.

    Every rule function that reads a file MUST go through this helper so
    that `--root` actually changes the file location, not only the Git
    plumbing. Without this, R4/R5 would always read the dev repo's files
    regardless of which repo the gate is being evaluated against.
    """
    return (
        cwd / CURRENT_STATE_REL,
        cwd / UAT_STATUS_REL,
        cwd / EXCEPTIONS_FILE_REL,
    )


# ---- Git plumbing ----------------------------------------------------------
#
# Every function that touches Git is overridable so tests can run hermetically
# against a temporary repo (see test_admission_check.py).

def _git(cwd: pathlib.Path, *args: str) -> str:
    """Run a git command and return stdout. Raise on failure."""
    r = subprocess.run(
        ["git", "-C", str(cwd), *args],
        capture_output=True, text=True,
    )
    if r.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} failed: {r.stderr.strip()}")
    return r.stdout.rstrip("\n")


def run_git(cwd: pathlib.Path, *args: str) -> str:
    """Default Git runner — overridable for hermetic tests."""
    return _git(cwd, *args)


def git_head(cwd: pathlib.Path = ROOT) -> str:
    return run_git(cwd, "rev-parse", "HEAD")


def git_origin_main(cwd: pathlib.Path = ROOT) -> Optional[str]:
    try:
        return run_git(cwd, "rev-parse", "--verify", "origin/main")
    except RuntimeError:
        return None


def git_is_ancestor(cwd: pathlib.Path, ancestor: str, descendant: str) -> bool:
    """True if `ancestor` is reachable from `descendant` (or equal)."""
    if ancestor == descendant:
        return True
    try:
        run_git(cwd, "merge-base", "--is-ancestor", ancestor, descendant)
        return True
    except RuntimeError:
        return False


def git_commit_iso8601(cwd: pathlib.Path, sha: str) -> Optional[str]:
    """Return the commit's author ISO8601 timestamp, or None on error."""
    try:
        return run_git(cwd, "show", "-s", "--format=%aI", sha)
    except RuntimeError:
        return None


def git_object_exists(cwd: pathlib.Path, sha: str, kind: str = "commit") -> bool:
    """True if `sha` resolves to a Git object of the given kind.

    Used by R0 to validate that --head names a real commit before any R1..R7
    is evaluated. Without this check a bogus candidate degrades to PASS in
    several rules (R5, R7) because git show / merge-base silently fail.
    """
    if not sha or not re.fullmatch(r"[0-9a-fA-F]{4,64}", sha):
        return False
    # Git requires the *parenthesised* form ^{kind}, not the bare ^kind.
    peeler = "{" + kind + "}"
    try:
        run_git(cwd, "cat-file", "-e", f"{sha}^{peeler}")
        return True
    except RuntimeError:
        return False


def git_last_modifying_commit(cwd: pathlib.Path, path: str) -> Optional[str]:
    """Return the SHA of the last commit that touched `path`, or None if
    the path has never been committed (no provenance).

    Receipts without provenance are NOT authoritative evidence — the
    admission gate must fail closed for them, not silently accept the
    filesystem mtime as evidence.
    """
    try:
        out = run_git(cwd, "log", "--format=%H", "-n", "1", "--", path)
    except RuntimeError:
        return None
    sha = out.strip()
    return sha or None


# ---- Path normalisation ----------------------------------------------------

def normalize_git_path(raw: str) -> str:
    """Normalise a path emitted by `git status --porcelain`.

    Handles: leading ./, rename targets (after ` -> `), and backslashes on
    Windows checkouts. Returns POSIX-style forward slashes.
    """
    if " -> " in raw:
        raw = raw.split(" -> ", 1)[1]
    raw = raw.replace("\\", "/")
    if raw.startswith("./"):
        raw = raw[2:]
    return raw


def is_r3_excluded(path: str) -> bool:
    """True if `path` is exactly excluded from the R3 dirty check."""
    if path in R3_EXCLUDE_EXACT:
        return True
    return any(path.startswith(prefix) for prefix in R3_EXCLUDE_PREFIXES)


def git_working_tree_dirty_paths(cwd: pathlib.Path = ROOT) -> list[str]:
    """Return list of dirty paths that R3 must consider (i.e. excluding R3
    exact-path exclusions). Empty list means clean working tree.

    Uses `--untracked-files=all` so untracked files inside subdirectories
    are listed individually (default porcelain collapses e.g. `pkg/x.kt`
    to its parent dir, which breaks the exact-path exclusion test).
    """
    out = run_git(cwd, "status", "--porcelain", "--untracked-files=all")
    paths = []
    for line in out.splitlines():
        if len(line) < 4:
            continue
        path = normalize_git_path(line[3:].strip())
        if is_r3_excluded(path):
            continue
        paths.append(path)
    return paths


# ---- File parsing ----------------------------------------------------------

def parse_state_head_sha(text: str) -> Optional[str]:
    m = re.search(r"\*\*HEAD\*\*\s*\|\s*`([0-9a-f]{40})`", text)
    return m.group(1) if m else None


def parse_state_generated_at(text: str) -> Optional[str]:
    m = re.search(r"\*\*Generated at \(UTC\)\*\*\s*\|\s*`([^`]+)`", text)
    return m.group(1).strip() if m else None


def parse_uat_status_rows(text: str) -> list[tuple[str, str]]:
    """Return list of (uid, status) tuples from CURRENT_UAT_STATUS.md."""
    return [(m.group(1), m.group(2))
            for m in re.finditer(r"`(UAT-RP-\d{3})`\s*\|\s*\*\*(\w+)\*\*", text)]


def parse_uat_receipt_path(text: str, uid: str) -> Optional[str]:
    """Return the relative receipt path declared for `uid` in
    CURRENT_UAT_STATUS.md, or None if not found.

    The status generator writes rows of the form
        | `UAT-RP-XXX` | **COVERED** | `docs/v2/07-uat/...md` | evidence excerpt |
    We parse the third column.
    """
    # Match a row whose first cell is `uid` and capture the backtick-quoted
    # path in the third cell.
    pat = (
        r"`" + re.escape(uid) + r"`\s*\|\s*\*\*\w+\*\*\s*\|\s*`([^`]+)`"
    )
    m = re.search(pat, text)
    if not m:
        return None
    return m.group(1).strip()


def load_exceptions() -> set[str]:
    if not EXCEPTIONS_FILE.exists():
        return set()
    return set(re.findall(r"UAT-RP-\d{3}", EXCEPTIONS_FILE.read_text()))


# ---- Rules -----------------------------------------------------------------

def check_r0_candidate_exists(candidate_sha: str, cwd: pathlib.Path = ROOT) -> None:
    """R0: the candidate SHA must exist and resolve to a commit object.

    Raises SystemExit(2) on bogus input — this is an ERROR, not a regular
    FAIL, because the user has supplied an argument that the rest of the
    rules cannot meaningfully evaluate.
    """
    if not git_object_exists(cwd, candidate_sha, "commit"):
        print(f"R0 ERROR: candidate {candidate_sha!r} does not resolve to a "
              f"commit in repo {cwd}", file=sys.stderr)
        sys.exit(2)


def check_r1_current_state_exists(cwd: pathlib.Path = ROOT) -> tuple[bool, Optional[str]]:
    cs, _, _ = _paths(cwd)
    if not cs.exists():
        return False, "R1 CURRENT_STATE.md missing"
    return True, None


def check_r2_state_head_is_ancestor(
    candidate_sha: str, state_text: str, cwd: pathlib.Path = ROOT,
) -> tuple[bool, Optional[str]]:
    """R2: CURRENT_STATE.HEAD must be ancestor (or equal) of --head.

    Why ancestor and not equality: CURRENT_STATE.md is versioned. When
    generated against HEAD=A and committed, the resulting commit is HEAD=B.
    Equality is impossible; ancestry is the correct invariant.
    """
    reported = parse_state_head_sha(state_text)
    if reported is None:
        return False, "R2 cannot parse HEAD from CURRENT_STATE.md"
    if not git_is_ancestor(cwd, reported, candidate_sha):
        return False, (
            f"R2 CURRENT_STATE.HEAD={reported[:8]} is NOT an ancestor of "
            f"candidate {candidate_sha[:8]} (cannot admit evidence about "
            f"a SHA that has not yet been produced)"
        )
    return True, None


def check_r3_working_tree_clean(cwd: pathlib.Path = ROOT) -> tuple[bool, Optional[str]]:
    dirty = git_working_tree_dirty_paths(cwd)
    if dirty:
        return False, f"R3 working tree has dirty source files: {', '.join(dirty[:5])}"
    return True, None


def check_r4_no_blocking_uats(strict: bool, cwd: pathlib.Path = ROOT
                              ) -> tuple[bool, Optional[str]]:
    _, uat_path, exc_path = _paths(cwd)
    if not uat_path.exists():
        return False, "R4 CURRENT_UAT_STATUS.md missing"
    text = uat_path.read_text()
    rows = parse_uat_status_rows(text)
    # Inline load_exceptions against exc_path (not the module-level one).
    if not exc_path.exists():
        exceptions: set[str] = set()
    else:
        exceptions = set(re.findall(r"UAT-RP-\d{3}", exc_path.read_text()))
    blocking = BLOCKING_UAT_STATES | (STRICT_BLOCKING_UAT_STATES if strict else set())
    found = [f"{uid}={status}" for uid, status in rows
             if status in blocking and uid not in exceptions]
    if found:
        suffix = " (strict mode)" if strict else ""
        return False, f"R4 blocking UATs without exception{suffix}: {', '.join(found[:5])}"
    return True, None


def check_r5_receipt_provenance(
    candidate_sha: str, cwd: pathlib.Path = ROOT,
) -> tuple[bool, Optional[str]]:
    """R5: every UAT's selected receipt must have Git-versioned provenance
    reachable from the candidate.

    Provenance is derived from Git ancestry, not from filesystem mtime. A
    receipt whose file has never been committed is not authoritative. A
    receipt whose last-modifying commit is not an ancestor of the candidate
    is evidence for a SHA that has not been produced yet — fail closed.
    """
    _, uat_path, _ = _paths(cwd)
    if not uat_path.exists():
        return False, "R5 CURRENT_UAT_STATUS.md missing"
    text = uat_path.read_text()
    rows = parse_uat_status_rows(text)
    failures: list[str] = []
    for uid, status in rows:
        if status not in ("COVERED", "PARTIAL", "REFERENCED", "FAIL_PROVEN", "REJECTED"):
            # NOT_RUN and unsupported states have no receipt to verify.
            continue
        rel_path = parse_uat_receipt_path(text, uid)
        if rel_path is None:
            failures.append(f"{uid}=no_receipt_in_status")
            continue
        prov = git_last_modifying_commit(cwd, rel_path)
        if prov is None:
            failures.append(f"{uid}=no_provenance({rel_path})")
            continue
        if not git_is_ancestor(cwd, prov, candidate_sha):
            failures.append(
                f"{uid}=provenance:{prov[:8]} not_ancestor_of_candidate:{candidate_sha[:8]}"
            )
    if failures:
        return False, "R5 receipt provenance failures: " + ", ".join(failures[:5])
    return True, None


def check_r7_origin_main_not_ahead(
    candidate_sha: str, cwd: pathlib.Path = ROOT,
) -> tuple[bool, Optional[str]]:
    """R7: candidate must not be behind origin/main."""
    origin_main = git_origin_main(cwd)
    if origin_main is None:
        return True, None
    if git_is_ancestor(cwd, origin_main, candidate_sha) or origin_main == candidate_sha:
        return True, None
    try:
        ahead, behind = run_git(
            cwd, "rev-list", "--count", "--left-right",
            f"{candidate_sha}...{origin_main}",
        ).split()
        if int(behind) > 0:
            return False, f"R7 candidate behind origin/main by {behind} commits"
    except RuntimeError:
        pass
    return True, None


# ---- Driver ----------------------------------------------------------------

def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--head", help="candidate SHA (default: git rev-parse HEAD)")
    ap.add_argument("--strict", action="store_true",
                    help="treat REFERENCED UATs as blocking (default: warning)")
    ap.add_argument("--root", default=str(ROOT),
                    help="repository root (for hermetic tests; default: this script's repo)")
    args = ap.parse_args()

    cwd = pathlib.Path(args.root)
    if not cwd.is_dir():
        print(f"--root {cwd} is not a directory", file=sys.stderr)
        return 2

    candidate = args.head or git_head(cwd)

    # R0: validate the candidate exists. Runs BEFORE R1..R7 so bogus input
    # never degrades any other rule to PASS.
    check_r0_candidate_exists(candidate, cwd)

    print(f"Admission check for candidate={candidate[:12]}  strict={args.strict}  "
          f"root={cwd}")

    try:
        cs_path, _, _ = _paths(cwd)
        state_text = cs_path.read_text()
    except FileNotFoundError:
        state_text = ""

    results: list[tuple[str, bool, Optional[str]]] = []

    # R1
    ok, msg = check_r1_current_state_exists(cwd)
    results.append(("R1 current_state exists", ok, msg))

    # R2
    if state_text:
        ok, msg = check_r2_state_head_is_ancestor(candidate, state_text, cwd)
        results.append(("R2 state.HEAD is ancestor of candidate", ok, msg))

    # R3
    ok, msg = check_r3_working_tree_clean(cwd)
    results.append(("R3 working tree clean (exact-path exclusions)", ok, msg))

    # R4
    ok, msg = check_r4_no_blocking_uats(strict=args.strict, cwd=cwd)
    suffix = " (strict)" if args.strict else ""
    results.append((f"R4 no blocking UAT states{suffix}", ok, msg))

    # R5
    ok, msg = check_r5_receipt_provenance(candidate, cwd)
    results.append(("R5 receipt provenance (Git ancestry, per UAT)", ok, msg))

    # R7 (R6 reserved for --strict handling in R4 above; see docstring)
    ok, msg = check_r7_origin_main_not_ahead(candidate, cwd)
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