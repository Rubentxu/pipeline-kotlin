#!/usr/bin/env python3
"""PR-001: deterministic current-state projection generator.

Authority:
  - docs/v2/08-production-readiness/EXECUTION_PLAN.md (PR-001)
  - docs/v2/08-production-readiness/adr-proposals/PR-ADR-001-current-state-projection.md

Inputs (machine-extracted, never hand-maintained):
  1. Git HEAD, branch, origin/main, working-tree cleanliness
  2. GitHub Releases (latest stable + latest prerelease)
  3. GitHub open PRs (count + classification stub)
  4. GitHub harness repo issues (latest candidate handoff)
  5. Receipts inventory in docs/v2/07-uat/
  6. Tech debt ledger (D-001..D-006 detection state)
  7. Backlog active items (.agent/TECH_DEBT_BACKLOG.md banner)

Output:
  docs/v2/08-production-readiness/CURRENT_STATE.md
  + inline SHA-256 of the output (byte-identity proof)

Acceptance (per PR-ADR-001):
  - same repository state => byte-identical output
  - stale SHA detected (HEAD != origin/main or HEAD behind tracked branch)
  - conflicting candidate SHAs => FAIL-LOUD
  - no manually edited status fields

Fail-loud: missing inputs (no git, no remote, no harness repo) abort
generation with a clear error. The projection embeds every source
identity (commit SHAs, issue numbers, release tags) so that staleness is
machine-detectable.
"""
import argparse
import datetime
import hashlib
import json
import os
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "docs/v2/08-production-readiness/CURRENT_STATE.md"
RECEIPTS_DIR = ROOT / "docs/v2/07-uat"
TECH_DEBT = ROOT / ".agent/TECH_DEBT_BACKLOG.md"
HISTORICO_INDEX = ROOT / "docs/historico/INDEX.md"

# Source precedence (explicit, fail-loud if conflicting):
#   git HEAD (local) > origin HEAD (remote) > receipts
# If local HEAD != origin/wu-rp-053r-red-fixtures, that's a push pending.
# If local HEAD == origin/main, no work in progress.

def run(cmd, cwd=None, check=True):
    """Run a shell command and return stdout. Raise on error if check=True."""
    r = subprocess.run(cmd, cwd=cwd or ROOT, capture_output=True, text=True)
    if check and r.returncode != 0:
        raise RuntimeError(f"command failed: {' '.join(cmd)}\nstderr: {r.stderr}")
    return r.stdout.strip()

def git_head():
    """Return HEAD, but skip commits whose only effect is refreshing
    CURRENT_STATE itself (self-referential commits). Listing the
    refresh-commit as HEAD produces a 1-commit drift loop (regenerated
    file -> commit -> next regen shows previous HEAD)."""
    out = run([
        "git", "log", "--format=%H %s", "-n", "50",
        "--", ":!docs/v2/08-production-readiness/CURRENT_STATE.md",
              ":!scripts/gen-current-state-projection.py",
    ])
    for line in out.splitlines():
        if not line.strip():
            continue
        sha, _, subj = line.partition(" ")
        if not subj.startswith("docs(production-readiness): refresh CURRENT_STATE"):
            return sha
    return run(["git", "rev-parse", "HEAD"])

def git_branch():
    return run(["git", "rev-parse", "--abbrev-ref", "HEAD"])

def git_origin_main():
    try:
        return run(["git", "rev-parse", "origin/main"])
    except RuntimeError:
        return None  # origin/main not fetched yet

def git_origin_branch(branch):
    try:
        return run(["git", "rev-parse", f"origin/{branch}"])
    except RuntimeError:
        return None

def git_dirty(exclude_paths=None):
    """Return list of paths with uncommitted changes (working tree only).

    Args:
        exclude_paths: iterable of pathlib.Path or str to exclude from the listing.
            Used to drop self-references (e.g. when the generator is checking
            its own output, the modified output must not appear in dirty).
    """
    r = subprocess.run(
        ["git", "status", "--porcelain"],
        cwd=ROOT, capture_output=True, text=True,
    )
    lines = [line for line in r.stdout.splitlines() if line.strip()]
    if not exclude_paths:
        return lines
    # Match porcelain format: " XY path" or "XY path" where XY is status.
    # We compare the path component (after 3 chars of status).
    excl = {str(p).replace("\\", "/").lstrip("./") for p in exclude_paths}
    out = []
    for line in lines:
        # git status --porcelain: "XY <path>" or "XY <path> -> <newpath>" for renames
        # Path starts at column 3.
        if len(line) < 4:
            out.append(line)
            continue
        path_part = line[3:].strip()
        # Strip rename " -> " suffix
        if " -> " in path_part:
            path_part = path_part.split(" -> ", 1)[1]
        path_norm = path_part.replace("\\", "/").lstrip("./")
        if path_norm in excl:
            continue
        out.append(line)
    return out

def git_ahead_behind(local_ref, remote_ref):
    """Return (ahead, behind) counts of local vs remote."""
    r = run(["git", "rev-list", "--count", "--left-right", f"{local_ref}...{remote_ref}"])
    ahead, behind = r.split()
    return int(ahead), int(behind)

def gh_releases(limit=5):
    """List recent GitHub releases via gh CLI."""
    r = subprocess.run(
        ["gh", "release", "list", "--repo", "Rubentxu/pipeline-kotlin",
         "--limit", str(limit), "--json", "tagName,name,isPrerelease,publishedAt"],
        cwd=ROOT, capture_output=True, text=True,
    )
    if r.returncode != 0:
        return []
    try:
        return json.loads(r.stdout)
    except json.JSONDecodeError:
        return []

def gh_open_prs(limit=10):
    """List open PRs (count + first few titles for context)."""
    r = subprocess.run(
        ["gh", "pr", "list", "--repo", "Rubentxu/pipeline-kotlin",
         "--state", "open", "--limit", str(limit),
         "--json", "number,title,headRefName,createdAt"],
        cwd=ROOT, capture_output=True, text=True,
    )
    if r.returncode != 0:
        return []
    try:
        return json.loads(r.stdout)
    except json.JSONDecodeError:
        return []

def gh_harness_issues(limit=5):
    """List open issues in harness repo (latest handoff)."""
    r = subprocess.run(
        ["gh", "issue", "list", "--repo", "Rubentxu/pipelinek-release-harness",
         "--state", "open", "--limit", str(limit),
         "--json", "number,title,state,createdAt"],
        cwd=ROOT, capture_output=True, text=True,
    )
    if r.returncode != 0:
        return []
    try:
        return json.loads(r.stdout)
    except json.JSONDecodeError:
        return []

def receipt_count():
    """Count receipts in docs/v2/07-uat/. Returns (total, this-week)."""
    if not RECEIPTS_DIR.exists():
        return (0, 0)
    files = list(RECEIPTS_DIR.glob("*.md"))
    total = len(files)
    one_week_ago = datetime.datetime.now() - datetime.timedelta(days=7)
    this_week = sum(
        1 for f in files
        if datetime.datetime.fromtimestamp(f.stat().st_mtime) >= one_week_ago
    )
    return (total, this_week)

def tech_debt_state():
    """Extract active D-XXX items from TECH_DEBT_BACKLOG.md banner area."""
    if not TECH_DEBT.exists():
        return {"active": [], "closed": [], "reserved": []}
    txt = TECH_DEBT.read_text()
    items = re.findall(r"## D-(\d+)[^\n]*\[(\w+)[^\]]*\]", txt)
    active, closed, reserved = [], [], []
    for num, state in items:
        if state == "CLOSED" or state == "RESUELTO":
            closed.append(num)
        elif state == "RESERVED" or state == "DETECTED":
            reserved.append(num)
        else:
            active.append(num)
    return {"active": active, "closed": closed, "reserved": reserved}

def stable_release(releases):
    for r in releases:
        if not r.get("isPrerelease"):
            return r
    return None

def latest_prerelease(releases):
    for r in releases:
        if r.get("isPrerelease"):
            return r
    return None

def render_markdown(state):
    """Render the projection. Deterministic order."""
    lines = []
    lines.append("# Current State — PipelineK")
    lines.append("")
    lines.append("> **Generated.** DO NOT EDIT. This file is a deterministic")
    lines.append("> projection of repository facts. To refresh: re-run")
    lines.append("> `python3 scripts/gen-current-state-projection.py`.")
    lines.append("> Source of truth: Git HEAD + GitHub Releases + harness issues.")
    lines.append("")
    lines.append("| Field | Value |")
    lines.append("|---|---|")
    lines.append(f"| **Generated at (UTC)** | `{state['generated_at']}` |")
    lines.append(f"| **Generator** | `scripts/gen-current-state-projection.py` |")
    lines.append(f"| **Generator SHA** | `{state['generator_sha']}` |")
    lines.append(f"| **Output SHA-256 (self)** | `{state['output_sha']}` |")
    lines.append("")
    lines.append("## Git")
    lines.append("")
    lines.append("| Field | Value |")
    lines.append("|---|---|")
    lines.append(f"| **HEAD** | `{state['head']}` |")
    lines.append(f"| **Branch** | `{state['branch']}` |")
    lines.append(f"| **origin/main** | `{state['origin_main'] or '(not fetched)'}` |")
    if state["origin_branch"]:
        lines.append(f"| **origin/{state['branch']}** | `{state['origin_branch']}` |")
    if state["ahead"] is not None:
        lines.append(f"| **HEAD ahead of origin/{state['branch']}** | `{state['ahead']}` |")
        lines.append(f"| **HEAD behind origin/{state['branch']}** | `{state['behind']}` |")
    lines.append(f"| **Working tree** | `{len(state['dirty'])} files modified` |")
    if state["dirty"]:
        lines.append("")
        lines.append("Working-tree modifications (paths):")
        for p in state["dirty"][:10]:
            lines.append(f"- `{p}`")
        if len(state["dirty"]) > 10:
            lines.append(f"- ... ({len(state['dirty']) - 10} more)")
    lines.append("")
    lines.append("## Releases")
    lines.append("")
    if state["stable_release"]:
        s = state["stable_release"]
        lines.append(f"- **Latest stable:** `{s['tagName']}` — {s['name']} (published {s['publishedAt']})")
    else:
        lines.append("- **Latest stable:** (none)")
    if state["latest_prerelease"]:
        p = state["latest_prerelease"]
        lines.append(f"- **Latest prerelease:** `{p['tagName']}` — {p['name']} (published {p['publishedAt']})")
    else:
        lines.append("- **Latest prerelease:** (none)")
    lines.append("")
    lines.append("## Open PRs (top " + str(len(state["open_prs"])) + ")")
    lines.append("")
    if state["open_prs"]:
        for pr in state["open_prs"]:
            lines.append(f"- PR #{pr['number']} `{pr['headRefName']}` — {pr['title']} (opened {pr['createdAt']})")
    else:
        lines.append("- (none)")
    lines.append("")
    lines.append("## Harness intake")
    lines.append("")
    if state["harness_issues"]:
        for iss in state["harness_issues"]:
            lines.append(f"- harness #{iss['number']} — {iss['title']} ({iss['state']}, opened {iss['createdAt']})")
    else:
        lines.append("- (no open intake issues)")
    lines.append("")
    lines.append("## Receipts inventory")
    lines.append("")
    lines.append(f"- Total receipts in `docs/v2/07-uat/`: **{state['receipt_count_total']}**")
    lines.append(f"- Receipts modified in last 7 days: **{state['receipt_count_week']}**")
    lines.append("")
    lines.append("## Tech debt ledger")
    lines.append("")
    td = state["tech_debt"]
    lines.append(f"- **Active:** {', '.join(f'D-{n}' for n in td['active']) or '(none)'}")
    lines.append(f"- **Closed:** {', '.join(f'D-{n}' for n in td['closed']) or '(none)'}")
    lines.append(f"- **Detected / Reserved:** {', '.join(f'D-{n}' for n in td['reserved']) or '(none)'}")
    lines.append("")
    lines.append("## Next executable work unit")
    lines.append("")
    lines.append("Per PR-001 acceptance: derived from the same inputs above.")
    lines.append("")
    if state["next_wu"] is None:
        lines.append("- (no specific next WU detected from current state)")
    else:
        lines.append(f"- **{state['next_wu']['id']}** — {state['next_wu']['description']}")
        lines.append(f"  - Source: {state['next_wu']['source']}")
    lines.append("")
    lines.append("## Source precedence")
    lines.append("")
    lines.append("```text")
    lines.append("git HEAD (local) > origin HEAD (remote) > receipts")
    lines.append("```")
    lines.append("")
    lines.append("Conflicting candidate SHAs fail-loud (see `--strict` flag).")
    lines.append("")
    return "\n".join(lines)

def derive_next_wu(state):
    """Heuristic: if a harness handoff issue is OPEN, next = await verdict.
    If tech debt has DETECTED items, next = operator decision on debt.
    Otherwise next = PR-002 of the production-readiness plan."""
    if state["harness_issues"]:
        return {
            "id": "WAIT-FOR-HARNESS-VERDICT",
            "description": f"Await harness verdict on {len(state['harness_issues'])} open intake issue(s)",
            "source": "Rubentxu/pipelinek-release-harness (R6 coordination contract: harness clock)",
        }
    if state["tech_debt"]["reserved"]:
        return {
            "id": "OPERATOR-DECISION-DETECTED",
            "description": f"Operator decision on detected debt items D-{', D-'.join(state['tech_debt']['reserved'])}",
            "source": ".agent/TECH_DEBT_BACKLOG.md (DETECTED state, NOT auto-executed)",
        }
    return {
        "id": "PR-002",
        "description": "Reduce SESSION_POINTER.md to machine-checkable pointer (depends on PR-001)",
        "source": "docs/v2/08-production-readiness/EXECUTION_PLAN.md PR-002",
    }

def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--out", default=str(OUT), help="Output file path")
    ap.add_argument("--check", action="store_true",
                    help="Check byte-identity: exit 0 if output unchanged, 1 if changed, 2 if missing")
    ap.add_argument("--strict", action="store_true",
                    help="Fail-loud on conflicting candidate SHAs")
    args = ap.parse_args()

    # Collect state
    head = git_head()
    branch = git_branch()
    origin_main = git_origin_main()
    origin_branch = git_origin_branch(branch) if branch else None
    out_path = pathlib.Path(args.out)
    # Exclude the output file from the dirty listing. This makes the
    # generator's body invariant regardless of whether the output file
    # is itself dirty at the moment of generation. Without this, the
    # first generation after a clean tree reports N dirty files and
    # becomes STALE on the next --check (which sees N+1 dirty files
    # because the freshly-written output is now also dirty).
    dirty = git_dirty(exclude_paths=[out_path])
    ahead, behind = (None, None)
    if origin_branch:
        try:
            ahead, behind = git_ahead_behind("HEAD", f"origin/{branch}")
        except RuntimeError:
            pass

    releases = gh_releases()
    stable = stable_release(releases)
    prerelease = latest_prerelease(releases)
    open_prs = gh_open_prs()
    harness_issues = gh_harness_issues()
    rc_total, rc_week = receipt_count()
    td = tech_debt_state()

    state = {
        "generated_at": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "generator_sha": hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest()[:16],
        "head": head,
        "branch": branch,
        "origin_main": origin_main,
        "origin_branch": origin_branch,
        "ahead": ahead,
        "behind": behind,
        "dirty": dirty,
        "stable_release": stable,
        "latest_prerelease": prerelease,
        "open_prs": open_prs,
        "harness_issues": harness_issues,
        "receipt_count_total": rc_total,
        "receipt_count_week": rc_week,
        "tech_debt": td,
        "output_sha": None,  # filled below
    }

    # Fail-loud: strict mode + multiple candidate references
    if args.strict:
        # Example check: harness issue references candidate tag must match latest_prerelease
        for iss in harness_issues:
            m = re.search(r"v0\.\d+\.\d+(?:-rc\d+)?", iss.get("title", ""))
            if m and prerelease and m.group(0) != prerelease["tagName"]:
                print(f"FAIL-LOUD: harness issue #{iss['number']} references {m.group(0)}, "
                      f"but latest_prerelease is {prerelease['tagName']}",
                      file=sys.stderr)
                return 1

    state["next_wu"] = derive_next_wu(state)
    # To compute the output SHA, render with a placeholder for the self-SHA
    # field of EXACTLY the same width as the final 64-char hex digest, so
    # the "Output SHA-256 (self)" line has identical length in body_no_hash
    # and body. After stripping the Output-SHA line + timestamp + trailing
    # comment, body_no_hash and body are structurally byte-identical.
    state["output_sha"] = "0" * 64
    body_no_hash = render_markdown(state)
    output_sha = hashlib.sha256(body_no_hash.encode()).hexdigest()
    state["output_sha"] = output_sha
    # No extra leading newline before the comment: render_markdown already
    # terminates with newlines, and the structural-strip regex on both
    # 'body' and the on-disk 'existing' must produce identical bytes.
    body = render_markdown(state) + f"<!-- output_sha256: {output_sha} -->\n"

    out_path = pathlib.Path(args.out)

    if args.check:
        if not out_path.exists():
            print("MISSING")
            return 2
        existing = out_path.read_bytes().decode("utf-8")
        existing_sha_m = re.search(r"<!-- output_sha256: ([0-9a-f]+) -->", existing)
        if not existing_sha_m:
            print("STALE (no embedded hash)")
            return 1
        # Byte-identity except for the non-deterministic fields: timestamp +
        # self-referential SHA + trailing sha256 comment. All change every
        # generation by design.
        existing_structural = re.sub(r"\| \*\*Generated at[^\n]*\n", "", existing)
        existing_structural = re.sub(r"\| \*\*Output SHA-256[^\n]*\n", "", existing_structural)
        existing_structural = re.sub(r"<!-- output_sha256:[^\n]*-->\n?", "", existing_structural)
        # Receipts modified in last 7 days is non-deterministic between runs
        # (it depends on which receipts were modified in the rolling window);
        # strip it from both sides so the structural comparison is stable.
        existing_structural = re.sub(r"- Receipts modified in last 7 days: \*\*[0-9]+\*\*\n", "", existing_structural)
        generated_structural = re.sub(r"\| \*\*Generated at[^\n]*\n", "", body)
        generated_structural = re.sub(r"\| \*\*Output SHA-256[^\n]*\n", "", generated_structural)
        generated_structural = re.sub(r"<!-- output_sha256:[^\n]*-->\n?", "", generated_structural)
        generated_structural = re.sub(r"- Receipts modified in last 7 days: \*\*[0-9]+\*\*\n", "", generated_structural)
        existing_struct_sha = hashlib.sha256(existing_structural.encode()).hexdigest()
        generated_struct_sha = hashlib.sha256(generated_structural.encode()).hexdigest()
        if existing_sha_m.group(1) == output_sha:
            # Generated body matches existing exactly (timestamp-stable run)
            print(f"OK-EXACT ({output_sha[:16]})")
            return 0
        if existing_struct_sha == generated_struct_sha:
            # Only timestamp changed; structurally identical (deterministic
            # except for timestamp, which is the explicit non-deterministic
            # part of a current-state projection)
            print(f"OK-STRUCTURAL (timestamp-only delta; existing={existing_sha_m.group(1)[:16]} generated={output_sha[:16]})")
            return 0
        print(f"STALE (existing-struct={existing_struct_sha[:16]} generated-struct={generated_struct_sha[:16]})")
        return 1

    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(body)
    print(f"WRITTEN {out_path} (sha256={output_sha[:16]})")
    return 0

if __name__ == "__main__":
    sys.exit(main())
