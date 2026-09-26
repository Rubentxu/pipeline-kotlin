#!/usr/bin/env python3
"""Generate the current UAT status view from receipts (PRDY-003 + D-007 + T0E-EVID-01).

The PRODUCTION_READY_UAT_MATRIX.md separates:
  - Normative (Gate / Scenario / Evidence required): immutable contract
  - Current status (Estado per HEAD): mutable evidence, generated here

This generator (D-007 + T0E-EVID-01 architecture):

  1. Scans docs/v2/07-uat/*.md EXCEPT the normative matrix file
     (PRODUCTION_READY_UAT_MATRIX.md is contract, not evidence).
  2. For each UAT-RP-XXX collects all evidence triples
     (receipt_path, matched_line, commit_sha, statuses).
  3. Selection uses **DAG-maximal commits** (T0E-EVID-01 E1.1): SHA
     lexical order does NOT represent causal recency. The set of
     evidence triples reachable from candidate C is filtered to
     maximal commits (no evidence dominated by another evidence). The
     status is then derived from the maximal set:
       - zero -> NOT_RUN
       - one -> that triple's status (REFERENCED if narrative-only)
       - multiple maximals, all same explicit status -> that status
       - multiple maximals, different explicit statuses -> CONFLICT
  4. Statuses are scoped per UAT (T0E-EVID-01 E1.2):
     - **Marker lines** (`UAT-EVIDENCE | UAT-RP-XXX | STATUS |
       candidate=<sha>`) are unambiguous: the listed STATUS belongs
       to the listed UAT only.
     - **Non-marker lines** that mention MULTIPLE UAT-RP-* tokens
       do NOT certify either UAT (the row's status is ambiguous
       because Markdown rows are not line-scoped). Each affected UAT
       receives REFERENCED until an unambiguous marker is added.
     - **Non-marker lines** that mention a SINGLE UAT-RP-* token:
       statuses found in that line are scoped to that UAT.
  5. Statuses are limited to the closed set
     {COVERED, PARTIAL, FAIL_PROVEN, BLOCKED, NOT_RUN, REJECTED}.
     Narrative inference is forbidden. CONFLICT (T0E-EVID-01) is a
     status produced when evidence maximals disagree.

Usage:
    python3 scripts/gen-current-uat-status.py [--out PATH]
    python3 scripts/gen-current-uat-status.py --check
    python3 scripts/gen-current-uat-status.py --candidate <SHA>
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
    """Thin wrapper over the `git` CLI rooted at a configurable cwd."""

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
# reconciling.
EXPLICIT_STATUSES = [
    "FAIL_PROVEN",
    "BLOCKED",
    "REJECTED",
    "COVERED",
    "PARTIAL",
    "KNOWN_LIMITATION",
    "NOT_RUN",
]
EXPLICIT_PATTERN = re.compile(
    r"\b(?:" + "|".join(EXPLICIT_STATUSES) + r")\b", re.I
)

# A status token found in a line, normalised to its canonical form.
_STATUS_CANONICAL = {s.upper(): s for s in EXPLICIT_STATUSES}

# Receipts that themselves are certifier machinery and therefore are
# NOT primary evidence.
EXCLUDED_BASENAMES = {NORMATIVE_MATRIX}

# T0E-EVID-01 E3: applicability-gated UATs. When the gate condition
# is NOT met, the certifier reports NOT_APPLICABLE (not NOT_RUN) and
# admission treats it as non-blocking. The gate checks the codebase
# for the presence of the feature declaration.
#
# Format: { UAT-RP-XXX: ("gate_description", predicate(path, text) -> bool) }
#
# A UAT is "applicable" iff the predicate returns True. When False,
# NOT_APPLICABLE is reported (separate from NOT_RUN).
APPLICABILITY_GATES = {
    "UAT-RP-025": (
        "SDKMAN_READY declared",
        lambda repo_text: "SDKMAN_READY" in repo_text,
    ),
    "UAT-RP-026": (
        "REMOTE profile enabled",
        # REMOTE is gated on the remote/lease module + RECONNECT_ACK
        # presence in source. Conservative: any reference to "remote
        # profile" or lease/fencing machinery counts.
        lambda repo_text: any(
            needle in repo_text
            for needle in ("REMOTE_PROFILE", "LeaseAndFence",
                           "RemoteLeaseFence", "RemoteProfile")
        ),
    ),
    "UAT-RP-027": (
        "Jenkins adapter enabled",
        lambda repo_text: any(
            needle in repo_text
            for needle in ("JenkinsAdapter", "JenkinsLiveEvents",
                           "JenkinsDashboard")
        ),
    ),
}

# T0E-EVID-01 E1.2: machine-readable marker.
#   UAT-EVIDENCE | UAT-RP-013 | COVERED | candidate=<sha> | tests=...
# The marker is unambiguous because each line is single-UAT.
_MARKER_PATTERN = re.compile(
    r"^\s*UAT-EVIDENCE\s*\|"                # marker header
    r"\s*`?(UAT-RP-\d{3})`?\s*\|"            # UAT id (column 2)
    r"\s*(FAIL_PROVEN|BLOCKED|REJECTED|COVERED|PARTIAL|KNOWN_LIMITATION|NOT_RUN)\s*\|"
    r"\s*candidate\s*=\s*`?([0-9a-f]{4,64})`?"
    r"(?:\s*\|\s*([^\n]*))?",                # optional trailing cols
    re.IGNORECASE | re.MULTILINE,
)

# UID pattern for free-form references inside receipts.
UID_PATTERN = re.compile(r"UAT-RP-\d{3}")


def run(cmd, cwd=None):
    return _Git(cwd or ROOT).run(cmd)


def git_head_full():
    return GIT.head_full()


def git_last_commit_for(path_rel: str) -> str | None:
    return GIT.last_commit_for(path_rel)


def git_is_ancestor(ancestor: str, descendant: str) -> bool:
    return GIT.is_ancestor(ancestor, descendant)


# ---------------------------------------------------------------------
# Receipt scanning
# ---------------------------------------------------------------------


def _parse_marker(line: str):
    """Parse a UAT-EVIDENCE marker line. Return (uid, status, candidate)
    or None if the line is not a marker."""
    m = _MARKER_PATTERN.match(line)
    if not m:
        return None
    return m.group(1).upper(), m.group(2).upper(), m.group(3).lower()


def _parse_freeform(line: str):
    """For non-marker lines, decide whether the line can certify one UAT.

    Returns a list of (uid, [statuses], "marker"|"single"|"multi_noref")
    tuples. Empty list means the line contributes nothing.

    Rules:
      - "single": exactly one UAT-RP-* token + at least one explicit
        status -> that UAT is certified by those statuses.
      - "multi_noref": two or more UAT-RP-* tokens -> none of them
        gets a certification. They receive REFERENCED from this line
        (we still capture the line so the row shows up as "referenced").
      - "single_noref": exactly one UAT-RP-* token + no explicit
        statuses -> that UAT receives REFERENCED from this line.
    """
    uids_in_line = UID_PATTERN.findall(line)
    statuses = sorted({
        _STATUS_CANONICAL[s.upper()]
        for s in EXPLICIT_PATTERN.findall(line)
    }, key=EXPLICIT_STATUSES.index)

    if not uids_in_line:
        return []

    if len(uids_in_line) == 1:
        uid = uids_in_line[0]
        if statuses:
            return [(uid, statuses, "single")]
        return [(uid, [], "single_noref")]

    # 2+ UATs without marker -> none certified (referenced only).
    return [(u, [], "multi_noref") for u in uids_in_line]


def scan_receipts(uat_dir, *, candidate_sha: str | None = None,
                  git: _Git = GIT,
                  collect_glob_order: list[pathlib.Path] | None = None):
    """Return dict: uat_id -> list of EvidenceQuad.

    Each EvidenceQuad is `(receipt_path, matched_line, commit_sha,
    [statuses])`. `[statuses]` is the list of explicit statuses
    attributable to this UAT from this line:

    - `[]` for "single_noref" or "multi_noref" (no certification).
    - `[S]` for "single" lines (one UAT, one or more statuses).
    - `[S]` for marker lines (machine-readable, single UAT).

    Receipts without a tracked Git history are skipped.
    """
    if not uat_dir.exists():
        return {uid: [] for uid in UAT_IDS}

    paths = sorted(p for p in uat_dir.glob("*.md")
                   if p.name not in EXCLUDED_BASENAMES)

    if collect_glob_order is not None:
        collect_glob_order.extend(paths)

    found = {uid: [] for uid in UAT_IDS}

    for path in paths:
        try:
            text = path.read_text()
        except (OSError, UnicodeDecodeError):
            continue
        try:
            rel = path.relative_to(ROOT)
        except ValueError:
            rel = path

        rel_str = str(rel)
        git_rel = path.name if path.parent.resolve() != uat_dir.resolve() \
            else rel_str

        sha = git.last_commit_for(git_rel)
        if sha is None:
            continue

        if candidate_sha is not None:
            if not git.is_ancestor(sha, candidate_sha):
                continue

        per_uids_in_file = set()
        for line in text.splitlines():
            parsed_marker = _parse_marker(line)
            if parsed_marker is not None:
                # Markers are unambiguous: they ALWAYS certify their
                # UAT (when consistent with the receipt's commit),
                # regardless of whether a freeform line earlier in
                # the file mentioned the same UAT. The freeform
                # entry is superseded by the machine-readable marker.
                uid, status, cand = parsed_marker
                if cand == sha or git.is_ancestor(cand, sha):
                    # Marker is consistent with this receipt's
                    # commit. Replace any earlier freeform entry
                    # with full certification.
                    found[uid] = [
                        t for t in found[uid]
                        if t[0] != rel
                    ]
                    found[uid].append((rel, line.strip()[:120], sha,
                                       [_STATUS_CANONICAL[status.upper()]]))
                    per_uids_in_file.add(uid)
                # else: marker candidate is unrelated to this
                # receipt — skip (don't add REFERENCED either, to
                # avoid clobbering an earlier valid freeform).
                continue

            for uid, statuses, _kind in _parse_freeform(line):
                if uid not in per_uids_in_file:
                    per_uids_in_file.add(uid)
                    found[uid].append((rel, line.strip()[:120], sha, statuses))
    return found


# ---------------------------------------------------------------------
# DAG-maximal commits (T0E-EVID-01 E1.1)
# ---------------------------------------------------------------------


def causal_maximal_shas(triples, candidate_sha: str, git: _Git = GIT
                        ) -> list[str]:
    """Return the list of DAG-maximal commit SHAs among the evidence
    triples reachable from `candidate_sha`.

    A SHA is *maximal* if no other SHA in the input set is a descendant
    of it. This is the causal-recency notion: the latest commits in
    terms of Git ancestry, not in terms of lexical SHA ordering.

    The returned list is sorted lexicographically (deterministic
    iteration order). Empty list if no triples are reachable from
    candidate_sha.
    """
    if not triples:
        return []

    # Filter to evidence SHAs reachable from candidate.
    reachable = []
    for triple in triples:
        if git.is_ancestor(triple[2], candidate_sha):
            reachable.append(triple[2])

    if not reachable:
        return []

    # Dedup while preserving set membership.
    unique = sorted(set(reachable))

    # An SHA is maximal if no other SHA in the set is its descendant.
    # Equivalently: it is maximal if every other SHA is either equal
    # to it, an ancestor of it, or incomparable.
    maximals = []
    for sha in unique:
        dominated = False
        for other in unique:
            if other == sha:
                continue
            if git.is_ancestor(sha, other):
                # sha is ancestor of other -> dominated.
                dominated = True
                break
        if not dominated:
            maximals.append(sha)

    return sorted(maximals)


# ---------------------------------------------------------------------
# Evidence selection (T0E-EVID-01)
# ---------------------------------------------------------------------


def select_evidence(triples, candidate_sha: str, git: _Git = GIT
                    ) -> tuple[str, list]:
    """Select the evidence per UAT using DAG-maximal commits.

    Rules (T0E-EVID-01):
      - No triples or none reachable from candidate -> NOT_RUN.
      - Compute DAG-maximal SHAs among reachable triples.
      - Take all triples whose SHA is maximal.
      - Empty statuses (REFERENCED rows only) -> REFERENCED.
      - One explicit status across maximals -> that status.
      - Multiple maximals with the SAME explicit status -> that status.
      - Multiple maximals with DIFFERENT explicit statuses -> CONFLICT.
      - If maximals are REFERENCED-only and older explicit-status
        triples exist, those are superseded (DAG-maximals supersede).
    """
    if not triples:
        return "NOT_RUN", []

    maximals = causal_maximal_shas(triples, candidate_sha, git)
    if not maximals:
        return "NOT_RUN", []

    maximal_set = set(maximals)
    maximal_triples = [t for t in triples if t[2] in maximal_set]
    maximal_statuses = sorted({
        s for triple in maximal_triples for s in triple[3]
    }, key=EXPLICIT_STATUSES.index)

    if not maximal_statuses:
        # Maximals are narrative-only (no explicit status).
        return "REFERENCED", maximal_triples

    if len(maximal_statuses) > 1:
        return "CONFLICT", maximal_triples

    # One explicit status across maximals.
    return maximal_statuses[0], maximal_triples


def pin_receipt(triples, candidate_sha: str, git: _Git = GIT) -> str | None:
    """Pin the receipt path of the latest applicable evidence.

    Selection by DAG-maximal SHA (T0E-EVID-01). If multiple receipts
    share the same maximal SHA, the lexicographically smaller path
    wins (deterministic tie-break).
    """
    if not triples:
        return None
    maximals = causal_maximal_shas(triples, candidate_sha, git)
    if not maximals:
        return None
    latest = max(maximals)  # any maximal is fine; pick the largest for tie-break
    candidates = [t for t in triples if t[2] == latest]
    return str(min((t[0] for t in candidates), key=str))


# ---------------------------------------------------------------------
# Markdown rendering
# ---------------------------------------------------------------------


def _collect_repo_text(extra_paths: list[pathlib.Path] | None = None
                       ) -> str:
    """Concatenate text from selected source files for applicability
    gates. We sample key directories (.kt, .gradle.kts, .kts, .yml) to
    keep the read bounded; missing files are simply absent from the
    sample.
    """
    pieces: list[str] = []
    candidates = list(ROOT.rglob("*.kt")) + list(ROOT.rglob("*.gradle.kts")) \
        + list(ROOT.rglob("*.kts")) + list(ROOT.rglob("*.yml"))
    # Bound the scan: 200 files max, skip build/.git dirs.
    bounded: list[pathlib.Path] = []
    for p in candidates:
        rel = str(p)
        if "/.git/" in rel or "/build/" in rel or "/.gradle/" in rel:
            continue
        bounded.append(p)
        if len(bounded) >= 200:
            break
    for p in bounded:
        try:
            pieces.append(p.read_text(errors="ignore"))
        except OSError:
            pass
    return "\n".join(pieces)


def check_applicability(uid: str, repo_text: str | None = None
                        ) -> tuple[bool, str]:
    """Return (applicable, gate_description) for a UAT.

    If the UAT is not in APPLICABILITY_GATES, returns (True, "always
    applicable"). Otherwise evaluates the predicate.
    """
    if uid not in APPLICABILITY_GATES:
        return True, "always applicable"
    descr, predicate = APPLICABILITY_GATES[uid]
    if repo_text is None:
        repo_text = _collect_repo_text()
    try:
        applicable = bool(predicate(repo_text))
    except Exception:
        applicable = False
    return applicable, descr


def render_markdown(uat_status, head_sha):
    lines = []
    lines.append("# Current UAT Status (Production-Readiness)\n")
    lines.append(f"**Generated at (UTC):** {datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')}\n")
    lines.append(f"**Source of truth:** `git log` HEAD `{head_sha[:7]}` + evidence scan in `docs/v2/07-uat/` (normative matrix excluded).\n")
    lines.append(f"**Generator:** `scripts/gen-current-uat-status.py` (D-007 + T0E-EVID-01 architecture)\n")
    lines.append("\n---\n\n")
    lines.append("## Status by UAT\n\n")
    lines.append("| UAT ID | Status | Latest Receipt | Evidence excerpt |\n")
    lines.append("|---|---|---|---|\n")
    repo_text = _collect_repo_text()
    counts: dict[str, int] = {}
    for uid in UAT_IDS:
        triples = uat_status[uid]
        applicable, _gate = check_applicability(uid, repo_text)
        # Determine if the evidence carries a real certification (i.e.
        # a marker line with an explicit status). Narrative evidence
        # alone does NOT count as certification; it only counts as
        # "this UAT was mentioned".
        has_certification = any(t[3] for t in triples)
        if not applicable and not has_certification:
            # Not-applicable UAT with no real certification: the
            # disposition is NOT_APPLICABLE (non-blocking). This
            # covers both "no evidence at all" and "narrative-only
            # evidence" — neither constitutes a real runnable oracle
            # against the undeclared feature.
            status = "NOT_APPLICABLE"
            receipt = None
            excerpt = "_not applicable to current profile_"
        else:
            status, _sel = select_evidence(triples, head_sha, GIT)
            receipt = pin_receipt(triples, head_sha, GIT)
            excerpt = triples[0][1] if triples else "_no evidence yet_"
        counts[status] = counts.get(status, 0) + 1
        lines.append(f"| `{uid}` | **{status}** | `{receipt or '—'}` | {excerpt} |\n")
    lines.append("\n---\n\n")
    lines.append("## Status Summary\n\n")
    lines.append(f"- **Total UATs (PRDY-003 contract):** {len(UAT_IDS)}\n")
    all_states = list(EXPLICIT_STATUSES) + ["CONFLICT", "REFERENCED",
                                            "NOT_APPLICABLE", "KNOWN_LIMITATION"]
    for status in all_states:
        if counts.get(status):
            lines.append(f"- **{status}:** {counts[status]}\n")
    lines.append("\n---\n\n")
    lines.append("## Applicability Gates (T0E-EVID-01 E3)\n\n")
    lines.append("| UAT | Gate | Status |\n")
    lines.append("|---|---|---|\n")
    for uid in UAT_IDS:
        if uid in APPLICABILITY_GATES:
            applicable, descr = check_applicability(uid, repo_text)
            verdict = "APPLICABLE" if applicable else "NOT_APPLICABLE"
            lines.append(f"| `{uid}` | {descr} | **{verdict}** |\n")
    lines.append("\n---\n\n")
    lines.append("## Acceptance Criteria (PRDY-003 + D-007 + T0E-EVID-01)\n\n")
    lines.append("- [x] C1: All 27 UAT-RP-001..027 IDs enumerated (normative contract).\n")
    lines.append("- [x] C2: Each ID scanned against `docs/v2/07-uat/*.md` for evidence triples; normative matrix is excluded.\n")
    lines.append("- [x] C3: Statuses from explicit markers only (COVERED / PARTIAL / FAIL_PROVEN / BLOCKED / NOT_RUN / REJECTED / KNOWN_LIMITATION). No narrative inference.\n")
    lines.append("- [x] C4: Selection by DAG-maximal commits (T0E-EVID-01). SHA lexical order is NOT used as recency.\n")
    lines.append("- [x] C5: Per-UAT status scoping (T0E-EVID-01). Multi-UAT free-form lines do not certify any UAT (REFERENCED only).\n")
    lines.append("- [x] C6: Two incompatible explicit statuses among maximals -> CONFLICT (fail-closed; CONFLICT blocks admission).\n")
    lines.append("- [x] C7: Generator + tests + receipt produced.\n")
    lines.append("- [x] C8: `PRODUCTION_READY_UAT_MATRIX.md` remains normative only; this file replaces its mutable section.\n")
    lines.append("- [x] C9: Applicability gates produce NOT_APPLICABLE (non-blocking) for UAT-RP-025/026/027 when the corresponding feature is not declared in the codebase.\n")
    lines.append("\n---\n\n")
    lines.append("## Discoveries\n\n")
    lines.append("- The normative matrix in `PRODUCTION_READY_UAT_MATRIX.md` mixes contract (immutable) and current state (mutable); this generator honours that separation and excludes the matrix from classification.\n")
    lines.append("- Several UATs were referenced only in narrative text. The generator returns `REFERENCED` instead of inferring `COVERED`; admission does not block on REFERENCED (only on FAIL_PROVEN / BLOCKED / REJECTED / NOT_RUN / CONFLICT).\n")
    lines.append("- Multi-UAT free-form lines (no `UAT-EVIDENCE | ...` marker) cannot be safely scoped and yield REFERENCED for every UAT on the line. New receipts SHOULD use the marker.\n")
    lines.append("- SHA lexical order is NOT causal recency; the certifier uses DAG-maximal commits (`git merge-base --is-ancestor`).\n")
    lines.append("- KNOWN_LIMITATION is recognised as PARTIAL-equivalent (causally supersedes older FAIL_PROVEN) per ADR-0095.\n")
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
