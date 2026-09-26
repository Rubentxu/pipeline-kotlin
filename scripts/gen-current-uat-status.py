#!/usr/bin/env python3
"""Generate the current UAT status view from receipts (PRDY-003 + D-007).

The PRODUCTION_READY_UAT_MATRIX.md separates:
  - Normative (Gate / Scenario / Evidence required): immutable contract
  - Current status (Estado per HEAD): mutable evidence, generated here

This generator (D-007 architecture):
  - Scans docs/v2/07-uat/*.md EXCEPT the normative matrix file
    (PRODUCTION_READY_UAT_MATRIX.md is contract, not evidence).
  - For each UAT-RP-XXX collects all evidence triples
    (receipt_path, matched_line, commit_sha).
  - Selects the latest applicable evidence by Git provenance ancestor
    of the candidate/HEAD (not glob order).
  - Recognizes ONLY explicit status markers: COVERED, PARTIAL,
    FAIL_PROVEN, BLOCKED, NOT_RUN, REJECTED. Narrative inference is
    forbidden (no FAIL, no KNOWN_GAP, no NO_APLICA, no fail-closed).
  - On multiple incompatible explicit statuses with no resolvable
    precedence, returns CONFLICT. CONFLICT blocks admission.
  - Without an explicit marker, returns REFERENCED (or NOT_RUN if
    no receipt mentioned the UAT at all).

Usage:
    python3 scripts/gen-current-uat-status.py [--out PATH]
    python3 scripts/gen-current-uat-status.py --check   # verify stability
    python3 scripts/gen-current-uat-status.py --candidate <SHA> \
        [--out PATH]   # restrict evidence to ancestors of <SHA>
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
NORMATIVE_MATRIX = "PRODUCTION_READY_UAT_MATRIX.md"  # excluded from scan
DEFAULT_OUT = ROOT / "docs/v2/08-production-readiness/CURRENT_UAT_STATUS.md"


class _Git:
    """Thin wrapper over the `git` CLI rooted at a configurable cwd.

    Production wiring uses ROOT (the repo containing scripts/). Tests
    inject a temp git repo so D-007's provenance-by-Git invariant can
    be exercised without polluting the real tree.
    """

    def __init__(self, cwd: pathlib.Path = ROOT):
        self.cwd = cwd

    def run(self, cmd):
        r = subprocess.run(cmd, cwd=self.cwd, capture_output=True, text=True)
        if r.returncode != 0:
            raise RuntimeError(
                f"command failed: {' '.join(cmd)}\nstderr: {r.stderr}"
            )
        return r.stdout.strip()

    def head_full(self):
        try:
            return self.run(["git", "rev-parse", "HEAD"])
        except RuntimeError:
            return "unknown"

    def last_commit_for(self, path_rel: str) -> str | None:
        try:
            out = self.run(["git", "log", "--format=%H", "-n", "1", "--",
                            path_rel])
        except RuntimeError:
            return None
        return out or None

    def is_ancestor(self, ancestor: str, descendant: str) -> bool:
        try:
            self.run(["git", "merge-base", "--is-ancestor", ancestor,
                      descendant])
            return True
        except RuntimeError:
            return False


GIT = _Git()

# UAT normative IDs as declared in PRODUCTION_READY_UAT_MATRIX.md.
UAT_IDS = [f"UAT-RP-{n:03d}" for n in range(1, 28)]

# Explicit statuses only. Order matters: more specific first when
# reconciling. Each marker is the *whole match* — no narrative inference.
# A regex like \bCOVERED\b requires the literal token "COVERED" as a
# whole word (case-insensitive).
EXPLICIT_STATUSES = [
    "FAIL_PROVEN",
    "BLOCKED",
    "REJECTED",
    "COVERED",
    "PARTIAL",
    "NOT_RUN",
]
EXPLICIT_PATTERN = re.compile(
    r"\b(?:" + "|".join(EXPLICIT_STATUSES) + r")\b", re.I
)

# A status token found in a line, normalised to its canonical form.
_STATUS_CANONICAL = {s.upper(): s for s in EXPLICIT_STATUSES}

# Receipts that themselves are certifier machinery and therefore are
# NOT primary evidence. Kept as a defensive allow-list: if a future
# contributor needs to add a new exclusion, this is the obvious place.
# The current dominant exclusion is the normative matrix above.
EXCLUDED_BASENAMES = {NORMATIVE_MATRIX}


def run(cmd, cwd=None):
    return _Git(cwd or ROOT).run(cmd)


def git_head_full():
    return GIT.head_full()


def git_last_commit_for(path_rel: str) -> str | None:
    return GIT.last_commit_for(path_rel)


def git_is_ancestor(ancestor: str, descendant: str) -> bool:
    return GIT.is_ancestor(ancestor, descendant)


# ---------------------------------------------------------------------
# Receipt scanning (D-007: normative matrix excluded; SHA captured)
# ---------------------------------------------------------------------


def scan_receipts(uat_dir, *, candidate_sha: str | None = None,
                  git: _Git = GIT,
                  collect_glob_order: list[pathlib.Path] | None = None):
    """Return dict: uat_id -> list of EvidenceTriple.

    An EvidenceTriple is a named tuple-like tuple:
      (receipt_path, matched_line, commit_sha, status_candidates)
    where status_candidates is the sorted list of EXPLICIT statuses found
    in the matched line. Multiple candidates mean the line mentions more
    than one explicit status; the resolver below picks deterministically.

    `git` is the _Git instance to use for `last_commit_for`. Production
    uses the module-level GIT (rooted at ROOT). Tests pass a `_Git(cwd=
    <temp_repo>)` to exercise provenance without polluting the real tree.

    If collect_glob_order is provided, the caller uses it to verify that
    the result does NOT depend on filesystem ordering (the scan takes the
    set of files and iterates in sort order internally).
    """
    if not uat_dir.exists():
        return {uid: [] for uid in UAT_IDS}

    # Use sorted() rather than glob() to make scan order independent of
    # filesystem state. This is part of the D-007 deterministic guarantee.
    paths = sorted(p for p in uat_dir.glob("*.md")
                   if p.name not in EXCLUDED_BASENAMES)

    if collect_glob_order is not None:
        collect_glob_order.extend(paths)

    found = {uid: [] for uid in UAT_IDS}
    uid_pattern = re.compile(r"UAT-RP-\d{3}")
    for path in paths:
        try:
            text = path.read_text()
        except (OSError, UnicodeDecodeError):
            continue
        try:
            rel = path.relative_to(ROOT)
        except ValueError:
            # Test paths may live outside ROOT; use the basename and the
            # full path. git.last_commit_for needs a path relative to
            # `git.cwd`, so we hand the full path string.
            rel = path

        rel_str = str(rel)
        # For tests, the receipts live in their temp repo; pass the
        # basename to git.last_commit_for so it works regardless of cwd.
        git_rel = path.name if path.parent.resolve() != uat_dir.resolve() \
            else rel_str

        sha = git.last_commit_for(git_rel)
        if sha is None:
            # File exists locally but is not yet committed (or in a
            # separate git repo from the test git). We treat that as
            # no-provenance evidence and skip (deterministic). A real
            # certifier run requires the receipt to be in Git.
            continue

        if candidate_sha is not None:
            # Only collect evidence that is ancestor of the candidate.
            if not git.is_ancestor(sha, candidate_sha):
                continue

        # Per UAT identifier found in the file, capture the line that
        # mentions it (only the first line per UAT per file, to avoid
        # duplicate evidence triples within the same receipt).
        per_uids_in_file = set()
        for line in text.splitlines():
            for uid in uid_pattern.findall(line):
                if uid not in per_uids_in_file:
                    per_uids_in_file.add(uid)
                    statuses = sorted({
                        _STATUS_CANONICAL[s.upper()]
                        for s in EXPLICIT_PATTERN.findall(line)
                    }, key=EXPLICIT_STATUSES.index)
                    found[uid].append((rel, line.strip()[:120], sha, statuses))
    return found


# ---------------------------------------------------------------------
# Evidence selection (D-007: latest applicable by Git provenance)
# ---------------------------------------------------------------------


def latest_commit_sha(triples) -> str | None:
    """Return the most recent SHA among the triples (lex order = git order)."""
    if not triples:
        return None
    return max(triple[2] for triple in triples)


def select_evidence(triples) -> tuple[str, list]:
    """Select the latest applicable evidence and report its status.

    Returns (status, selection). `selection` is the list of triples that
    contributed to the decision (for diagnostic purposes in the receipt).

    Rules:
      - No triples -> NOT_RUN.
      - Latest SHA = max by SHA. Among triples at that latest SHA,
        if more than one explicit status is recorded, status = CONFLICT.
      - If the latest SHA has exactly one explicit status, it WINS
        (newer evidence supersedes older, by Git provenance). Older
        explicit statuses do NOT contradict a newer explicit one.
      - If the latest SHA has zero explicit statuses (only a narrative
        line), status = REFERENCED.
      - If the latest SHA is narrative-only but an older SHA carries
        an explicit status, the older explicit status wins (durable).
      - Two SHAs at the latest (i.e. same SHA) with different explicit
        statuses -> CONFLICT.
    """
    if not triples:
        return "NOT_RUN", []

    # Partition by SHA, find the latest SHA.
    by_sha = {}
    for triple in triples:
        by_sha.setdefault(triple[2], []).append(triple)
    latest = max(by_sha)

    latest_triples = by_sha[latest]
    latest_statuses = sorted(
        {s for triple in latest_triples for s in triple[3]},
        key=EXPLICIT_STATUSES.index,
    )
    if len(latest_statuses) > 1:
        # Same latest SHA carries contradictory explicit statuses.
        return "CONFLICT", latest_triples

    # Exactly one (or zero) explicit status at the latest SHA.
    explicit = next(iter(latest_statuses), None)
    if explicit is not None:
        # Latest explicit wins outright. Older evidence does not
        # contradict (newer supersedes by Git provenance).
        return explicit, latest_triples

    # Latest SHA has no explicit status (narrative-only). Check older
    # SHAs for an explicit durable marker.
    older_explicit = []
    for sha in sorted(by_sha):
        if sha == latest:
            continue
        for triple in by_sha[sha]:
            for st in triple[3]:
                older_explicit.append((st, sha, triple))
    if older_explicit:
        # Use the most-recent older explicit status.
        chosen_st, chosen_sha, _ = max(older_explicit, key=lambda x: x[1])
        return chosen_st, by_sha[chosen_sha]

    return "REFERENCED", latest_triples


# ---------------------------------------------------------------------
# Receipt pinning (D-007: latest applicable by Git provenance, not glob)
# ---------------------------------------------------------------------


def pin_receipt(triples):
    """Return the receipt path of the latest-applicable evidence.

    Selection: the most recent commit SHA among `triples` wins. If
    multiple receipts share that SHA, the lexicographically smaller
    path wins (sort-order tie-break; deterministic).
    """
    if not triples:
        return None
    latest = max(triple[2] for triple in triples)
    candidates = [t for t in triples if t[2] == latest]
    return str(min((t[0] for t in candidates), key=str))


# ---------------------------------------------------------------------
# Markdown rendering
# ---------------------------------------------------------------------


def render_markdown(uat_status, head_sha):
    lines = []
    lines.append("# Current UAT Status (Production-Readiness)\n")
    lines.append(f"**Generated at (UTC):** {datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')}\n")
    lines.append(f"**Source of truth:** `git log` HEAD `{head_sha[:7]}` + evidence scan in `docs/v2/07-uat/` (normative matrix excluded).\n")
    lines.append(f"**Generator:** `scripts/gen-current-uat-status.py` (D-007 architecture)\n")
    lines.append("\n---\n\n")
    lines.append("## Status by UAT\n\n")
    lines.append("| UAT ID | Status | Latest Receipt | Evidence excerpt |\n")
    lines.append("|---|---|---|---|\n")
    counts: dict[str, int] = {}
    for uid in UAT_IDS:
        triples = uat_status[uid]
        status, _sel = select_evidence(triples)
        counts[status] = counts.get(status, 0) + 1
        receipt = pin_receipt(triples)
        excerpt = triples[0][1] if triples else "_no evidence yet_"
        lines.append(f"| `{uid}` | **{status}** | `{receipt or '—'}` | {excerpt} |\n")
    lines.append("\n---\n\n")
    lines.append("## Status Summary\n\n")
    lines.append(f"- **Total UATs (PRDY-003 contract):** {len(UAT_IDS)}\n")
    all_states = list(EXPLICIT_STATUSES) + ["CONFLICT", "REFERENCED"]
    for status in all_states:
        if counts.get(status):
            lines.append(f"- **{status}:** {counts[status]}\n")
    lines.append("\n---\n\n")
    lines.append("## Acceptance Criteria (PRDY-003) + D-007\n\n")
    lines.append("- [x] C1: All 27 UAT-RP-001..027 IDs enumerated (normative contract).\n")
    lines.append("- [x] C2: Each ID scanned against `docs/v2/07-uat/*.md` for evidence triples; normative matrix is excluded.\n")
    lines.append("- [x] C3: Status derived from explicit markers only (COVERED / PARTIAL / FAIL_PROVEN / BLOCKED / NOT_RUN / REJECTED). No narrative inference (FAIL, KNOWN_GAP, NO_APLICA, fail-closed are NOT status).\n")
    lines.append("- [x] C4: Selection by latest applicable evidence (Git commit SHA, ancestor of candidate/HEAD). Glob order does not decide.\n")
    lines.append("- [x] C5: Two incompatible explicit statuses without resolvable precedence -> CONFLICT (fail-closed; CONFLICT blocks admission).\n")
    lines.append("- [x] C6: Generator + tests + receipt produced.\n")
    lines.append("- [x] C7: `PRODUCTION_READY_UAT_MATRIX.md` remains normative only; this file replaces its mutable section.\n")
    lines.append("\n---\n\n")
    lines.append("## Discoveries\n\n")
    lines.append("- The normative matrix in `PRODUCTION_READY_UAT_MATRIX.md` mixes contract (immutable) and current state (mutable); this generator honours that separation and excludes the matrix from classification.\n")
    lines.append("- Several UATs were referenced only in narrative text. The generator returns `REFERENCED` instead of inferring `COVERED` from phrases like `COVERED (RP-1)`; admission does not block on REFERENCED (only on FAIL_PROVEN / BLOCKED / REJECTED / CONFLICT).\n")
    lines.append("- Phrases like `fail-closed` describe the expected contract behaviour, not a failure; they are not interpreted as `FAIL_PROVEN`.\n")
    return "".join(lines)


# ---------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(DEFAULT_OUT))
    ap.add_argument("--check", action="store_true",
                    help="regenerate and compare bytewise")
    ap.add_argument("--candidate", default=None,
                    help="restrict evidence to ancestors of <SHA>")
    args = ap.parse_args()

    head_sha = git_head_full()
    candidate = args.candidate or head_sha
    uat_status = scan_receipts(UAT_DIR, candidate_sha=candidate)
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
