# Current State — PipelineK

> **Generated.** DO NOT EDIT. This file is a deterministic
> projection of repository facts. To refresh: re-run
> `python3 scripts/gen-current-state-projection.py`.
> Source of truth: Git HEAD + GitHub Releases + harness issues.

| Field | Value |
|---|---|
| **Generated at (UTC)** | `2026-09-26T09:44:46Z` |
| **Generator** | `scripts/gen-current-state-projection.py` |
| **Generator SHA** | `9efe3cf69828cdaa` |
| **Output SHA-256 (self)** | `3688dd6fb1e554b14b36df7d836774c32fac698002c964088eda71c87de0562e` |

## Git

| Field | Value |
|---|---|
| **HEAD** | `c3c13c18d1b97aa0bc2f884ac9f67338203f163d` |
| **Branch** | `wu/rp-053r-red-fixtures` |
| **origin/main** | `acc903875d70f939713786d71a6331bb6ccf7dc9` |
| **origin/wu/rp-053r-red-fixtures** | `0ce46af5280c17838bec9e245a9cd92d48a3f364` |
| **HEAD ahead of origin/wu/rp-053r-red-fixtures** | `0` |
| **HEAD behind origin/wu/rp-053r-red-fixtures** | `0` |
| **Working tree** | `4 files modified` |

Working-tree modifications (paths):
- ` M .agent/SESSION_POINTER.md`
- ` M .agent/TECH_DEBT_BACKLOG.md`
- ` M .agent/WORK_JOURNAL.md`
- ` M scripts/gen-current-state-projection.py`

## Releases

- **Latest stable:** (none)
- **Latest prerelease:** `v0.40.0-rc1` — pipelinek 0.40.0-rc1 — ExecutionContext & ScmGitCheckout CERTIFIED (release candidate) (published 2026-09-26T08:37:59Z)

## Open PRs (top 10)

- PR #97 `wu/rp-053-coherence-characterization` — test+docs(workspace): WU-RP-053 coherence contract characterization + HAR-007 gap (opened 2026-09-24T17:19:20Z)
- PR #96 `wu/rp-053-cut5-stash-cwd` — fix(workspace): scope stash and unstash to effective cwd (opened 2026-09-24T13:52:17Z)
- PR #95 `wu/rp-053-cut4-deletedir-cwd` — fix(workspace): route deleteDir through effective cwd (opened 2026-09-24T13:47:10Z)
- PR #94 `docs/agents-rc-meridian` — docs(agents): operative meridian M1..M8 for release candidates (opened 2026-09-24T13:17:14Z)
- PR #93 `wu/disable-actions-pr-trigger-rp-harness` — ci(actions): drop pull_request trigger on LPR-0 CI (opened 2026-09-24T13:03:16Z)
- PR #92 `wu/rp-053-first-cut-closure-receipt` — docs(uat): WU-RP-053 first vertical cut closure receipt (opened 2026-09-24T12:59:01Z)
- PR #91 `wu/rp-043-rebase-on-0a62cb82` — perf(wu-rp-043): integration clean (rebased on main 0a62cb82) (opened 2026-09-24T12:56:32Z)
- PR #90 `wu/rp-053-promote-operator-wip` — fix(workspace): split authorizedWorkspaceRoot and effective cwd (WU-RP-053) (opened 2026-09-24T12:47:11Z)
- PR #71 `dependabot/gradle/v2/org.xerial-sqlite-jdbc-3.53.4.0` — chore(deps): bump org.xerial:sqlite-jdbc from 3.46.1.3 to 3.53.4.0 in /v2 (opened 2026-09-23T15:20:02Z)
- PR #70 `dependabot/gradle/v2/com.google.protobuf-0.10.0` — chore(deps): bump com.google.protobuf from 0.9.4 to 0.10.0 in /v2 (opened 2026-09-23T15:19:57Z)

## Harness intake

- harness #3 — Candidate handoff: pipelinek v0.40.0-rc1 — ExecutionContext & ScmGitCheckout CERTIFIED (OPEN, opened 2026-09-26T08:38:25Z)

## Receipts inventory

- Total receipts in `docs/v2/07-uat/`: **272**
- Receipts modified in last 7 days: **105**

## Tech debt ledger

- **Active:** (none)
- **Closed:** D-001, D-002, D-003, D-004
- **Detected / Reserved:** D-005, D-006

## Next executable work unit

Per PR-001 acceptance: derived from the same inputs above.

- **WAIT-FOR-HARNESS-VERDICT** — Await harness verdict on 1 open intake issue(s)
  - Source: Rubentxu/pipelinek-release-harness (R6 coordination contract: harness clock)

## Source precedence

```text
git HEAD (local) > origin HEAD (remote) > receipts
```

Conflicting candidate SHAs fail-loud (see `--strict` flag).
<!-- output_sha256: 3688dd6fb1e554b14b36df7d836774c32fac698002c964088eda71c87de0562e -->
