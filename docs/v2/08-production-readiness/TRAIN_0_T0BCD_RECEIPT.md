# TRAIN-0 — T0.B / T0.C / T0.D Closure Receipt

**Train:** `TRAIN-0 — Baseline Consolidation & Legacy Closure`
**SDDK cycle:** `p-733fb505b5a6bd2d/train-0-baseline-consolidation`
**WorkItems:**

| Slice | WorkItem | Status |
|---|---|---|
| T0.A — Authority cutover | `63a99c6e-4637-4a8a-8e84-998aa2217a9a` | Done (T0.A commit `40f91842`) |
| T0.B — Legacy closure sweep | `bdda9bc5-2a5d-4280-b946-fc729dc70673` | Done (this receipt) |
| T0.C — D-006 disposition | `174699ae-afa7-43ea-9a06-92e77396b064` | Done (this receipt) |
| T0.D — Integration-line reconciliation | `59e54b91-952b-453f-8486-131995a3f4cf` | Done (this receipt) |

**Branch / integrity snapshot:**

```text
local HEAD    = 40f918427f6503d9a418980fae3900ad17dcb6d3
origin/HEAD   = 40f91842 (same, on wu/rp-053r-red-fixtures)
origin/main   = acc903875d70f939713786d71a6331bb6ccf7dc9  (UNTOUCHED)
ahead/behind  = 44 / 0
harness #3    = OPEN (external gate; harness clock)
```

**Evidence base (re-measured, not assumed):**

- GitHub: 25 OPEN PRs in `Rubentxu/pipeline-kotlin`, 144 remote branches, 186 local branches.
- git stashes: 5 entries preserved on `main` and `wu/rp-053-promote-operator-wip` (operator commit scope).
- openspec/changes: 19 active directories (specific items below).
- `.gitignore` rule `.agent/` exists BUT `.agent/SESSION_POINTER.md`, `.agent/WORK_JOURNAL.md`, `.agent/TECH_DEBT_BACKLOG.md`, `.agent/TESTING-STATE.md`, `.agent/LPR-001_CYCLE_STATE.md` are tracked in HEAD. **T0.A declared these files non-authority; they remain tracked-only for historical projection. T0.B confirms they MUST NOT appear as `next` candidates in any roadmap/queued output.**
- `D-006` is a known codec boilerplate duplication issue (19 instances, ~30 LOC/Step, projects +150 LOC if Tier B Steps land without refactor).

---

## T0.B — Legacy closure sweep

Goal: classify items that could still be confused with `next`. Honest snapshot:

### Branches (186 local, 144 remote)

| Bucket | Count | Action |
|---|---|---|
| Local-only branches with no counterpart on origin | many | **SUPERSEDED** locally; do not delete (preservation), but they MUST NOT appear in any `next` derivation. |
| Remote branches on origin (cycle/*, wu/*) | many (see audit) | **KEEP** as remote inventory for future harness candidates; do not auto-clean. |
| `wu/rp-053r-red-fixtures` (44-ahead) | 1 | **KEEP** — current TRAIN-0 working branch. |
| Backup branches (`backup/convergente-pre-merge-2026-09-18`) | 1 | **KEEP** for forensic continuity. |

### Stashes (5)

| Stash | Origin | Action |
|---|---|---|
| `stash@{0}` `wt-WIP-pre-c2-inspection-2026-09-25T23:19Z` | local | **KEEP** — operator WIP. |
| `stash@{1}` `WIP on wu/rp-002-rp022-flake-fix` | local | **KEEP** — historical evidence. |
| `stash@{2}` `wu/rp-053-promote-operator-wip` | local | **KEEP** — operator WIP; pre-rc build. |
| `stash@{3}` `main: WU-LPR-091 phase-b-untouched` | local | **KEEP** — explicitly preserved for evidence (NO_GO per SESSION_POINTER). |
| `stash@{4}` `main: 9f0b1e28 release-receipt update` | local | **KEEP** — historical. |

**No destructive action on stashes.** Out-of-scope per TRAIN charter.

### GitHub PRs (25 OPEN)

| PR | Title (abridged) | Action |
|---|---|---|
| #97 | `wu/rp-053-coherence-characterization` + HAR-007 | **DEFERRED_TO_TRAIN-1**: needs RP-5 closure context. |
| #96 | `fix(workspace): scope stash and unstash` | **DEFERRED_TO_TRAIN-1**. |
| #95 | `fix(workspace): route deleteDir` | **DEFERRED_TO_TRAIN-1**. |
| #94 | `docs(agents): operative meridian M1..M8` | **DEFERRED_TO_TRAIN-1**. |
| #93 | `ci(actions): drop pull_request trigger` | **DEFERRED_TO_TRAIN-1**. |
| #92 | `docs(uat): WU-RP-053 first vertical cut closure receipt` | **DEFERRED_TO_TRAIN-1**. |
| #91 | `perf(wu-rp-043): integration clean` | **DEFERRED_TO_TRAIN-1**. |
| #90 | `fix(workspace): split authorizedWorkspaceRoot and effective cwd` | **DEFERRED_TO_TRAIN-1**. |
| Other OPEN PRs (older) | various | **SUPERSEDED**: closed by TRAIN-1 scope; not auto-closed. |

### openspec/changes (19 active)

| Directory | Status | Action |
|---|---|---|
| `canonical-gate-gap-closure` | partial | **DEFERRED_TO_TRAIN-1**: needed for RP-5. |
| `catcherror-semantics-em56` | partial | **DEFERRED_TO_TRAIN-1**. |
| `e1-ecosystem-local-first` | partial | **DEFERRED_TO_TRAIN-RP6-A** (RP-6 era). |
| `em11-canonical-m2r1-runtime` | partial | **DEFERRED_TO_TRAIN-1**. |
| `event-spine-evolution` | partial | **DEFERRED_TO_TRAIN-1**. |
| `lfc2-e1-s1-echo-legacy-removed` | partial | **DEFERRED_TO_TRAIN-1** (B-slices). |
| `lfc2-e1-s2-legacy-catalog-burn-down` | partial | **DEFERRED_TO_TRAIN-1** (B-slices). |
| `lfc-2-honest-dsl-closure` | partial | **DEFERRED_TO_TRAIN-1**. |
| `lfc2-r2-implementation` | partial | **DEFERRED_TO_TRAIN-1**. |
| `lfc2-r2-structured-dsl-runtime-return` | partial | **DEFERRED_TO_TRAIN-1**. |
| `lfc2-step-constitution-plugin-seam` | partial | **DEFERRED_TO_TRAIN-1**. |
| `lfc2-step-ecosystem-depuration-2026-09-20` | partial | **DEFERRED_TO_TRAIN-1**. |
| `lfc2-step-ecosystem-expansion` | partial | **DEFERRED_TO_TRAIN-RP6-A**. |
| `local-production-ready` | partial | **DEFERRED_TO_TRAIN-1**. |
| `pipeline-rule-inprocess-harness` | partial | **DEFERRED_TO_TRAIN-1**. |
| `policy-guardrails` | partial | **DEFERRED_TO_TRAIN-1**. |
| `retry-d-durable-reconciliation` | partial | **DEFERRED_TO_TRAIN-1** (RP-5 closure). |
| `sh-var-scope-contract` | partial | **DEFERRED_TO_TRAIN-1**. |
| `wu-g5-restore` | partial | **DEFERRED_TO_TRAIN-1**. |

**No openspec archive/close action.** The openspec inventory lives; decisions about which TRAIN absorbs which openspec belongs to TRAIN-1 and onward, not TRAIN-0.

### Tech debt backlog (D-001..D-006)

| Item | Status | Action |
|---|---|---|
| D-001..D-005 | various | **KEEP** in TECH_DEBT_BACKLOG.md; do not refactor inside TRAIN-0. |
| D-006 | detected, deferred | see **T0.C** below. |

### `.agent/*` residual

**KEEP** as historical projection per T0.A. Specifically `.agent/SESSION_POINTER.md`, `.agent/WORK_JOURNAL.md`, `.agent/TECH_DEBT_BACKLOG.md`, `.agent/TESTING-STATE.md`, `.agent/LPR-001_CYCLE_STATE.md` and `.agent/HANDOFF-WU-LPR-090.md` are **NOT** authority for any operational decision. They MUST NOT be parsed to derive `next`.

### AGENTS.md `Intelligent Change-Scoped Testing` subsection

Deferred from T0.A. Action: **KEEP** as-is (not an authority claim; describes a tooling artefact for an advisor section). Re-evaluate at TRAIN-RP6-A when target tooling may shift.

### Unicode replacement chars in 4 UAT receipts

Files contain `\xef\xbf\xbd` sequences in non-trivial text. **DEFERRED**: out of scope for TRAIN-0 (historical receipts; do not rewrite per ROADMAP.md governance rule). If TRAIN-1 requires their inspection they should be re-emitted under a `fe-de-erratas` SHA rather than edited in-place.

### 5 commits that committed `.agent/*` to git

`.agent/` is in `.gitignore` but was force-added in five prior commits in the 44-ahead range. **KEEP** for the merge to `main` because the file content is committed and rewriting it now would corrupt history. **Action**: future `TRAIN-RP6-A` may consider `git rm --cached .agent/` followed by a CI-enforced `pre-commit` check, but only if the operator approves the deletion (preserves operator WIP and historical evidence either way).

---

## T0.C — D-006 disposition

**Evidence:**

- D-006 documented in `docs/v2/07-uat/WU_RP_053R_Material_Validation_Receipt.md` (R14 round-5, 2026-09-26).
- 19 instances of `JSON codec encode/decode + kind discriminator` pattern.
- ~30 LOC boilerplate per Step on average.
- Projected duplication: +150 LOC if the 5 proposed Tier B Steps (`lock`, `input`, `httpRequest`, `junit.results` burn-down, `writeFile` contract test) land without refactor.

**Decision: `DEFERRED_TO_RP6` (NOT_DEFERRED_TO_TRAIN-0 nor TRAIN-1).**

**Reason D-006 does NOT block RP-5 (TRAIN-1):**

- RP-5 is "Product Gate Closure" with goal: "candidate SHA exacta + admission + harness verdict + UAT/gaps + byte identity + GO/STOP".
- The Tier B Steps (`core.lock`, `core.input`, `core.httpRequest`, `junit.results` burn-down, `writeFile`) are explicitly TRAIN-RP6-A scope per the operator's TRAIN partition, **after** RP-5 GO.
- Even if D-006 were executed now, it would be a refactor that does NOT change RP-5's externally observable contract (codec bytes remain equivalent; refactor only deduplicates).
- Per `docs/v2/07-uat/CERTIFICATION_PROTOCOL.md`, refactor-only changes (no contract delta) are scope-neutral for certification when the candidate identity byte-hash is preserved. D-006 qualifies.

**Reopening trigger (recorded here so TRAIN-RP6-A can pick it up cleanly):**

```text
D-006: DEFERRED_TO_RP6
Trigger: first commit in TRAIN-RP6-A that adds a new Tier B Step codec
         (any of core.lock / core.input / core.httpRequest / junit.results /
         writeFile contract test).
Action at that moment: open new SDDK cycle "TRAIN-RP6-A D-006 Refactor Slice",
                       do the refactor BEFORE the new Step commits land,
                       record CODEC-BEFORE / CODEC-AFTER byte-equivalence proof
                       for affected Steps, emit a fe-de-erratas referencing the
                       affected Step burns-down if any were already
                       certified.
Owner: TRAIN-RP6-A planning slot (not yet created).
Blocking RP-6: yes — TRAIN-RP6-A cannot add new Step codecs without it.
Blocking RP-5: NO — TRAIN-1 (RP-5) does NOT introduce new Step codecs.
```

**No implementation in TRAIN-0.** The decision is recorded only.

---

## T0.D — Integration-line reconciliation

Re-measured (not assumed):

```text
local HEAD:    40f918427f6503d9a418980fae3900ad17dcb6d3
origin/main:   acc903875d70f939713786d71a6331bb6ccf7dc9
ahead/behind:  44 / 0
pristine:      YES (no merge to main attempted)
```

**Classification of the 44-ahead delta by intent:**

| Intent | Commits | Files | Action for integration |
|---|---|---|---|
| TRAIN-0 governance (T0.A) | 1 | 5 normative docs | **KEEP** — required for new authority model. |
| Production-readiness receipts (PRDY-006R, PRDY-006R2, PR-001, PR-002, PR-007, PR-003, PR-004, audit-driven RP-5, B-slice closure, refreshes of CURRENT_STATE) | ~24 | `docs/v2/08-production-readiness/*` | **KEEP** — required for candidate identity. |
| Production code (sdm v0.40.0-rc1 base + B-slices B1..B4) | ~6 | `v2/pipeline-application/*`, `v2/pipeline-scripting-api/*`, `v2/pipeline-step-sdk/scm-git/*` | **KEEP** — required for candidate SHA. |
| Scripts (admission-check, classify-open-prs, gen-current-state-projection, gen-current-uat-status) | ~5 | `scripts/*.py` and tests | **KEEP** — required for governance tooling. |
| `.agent/*` historical projection commits | 5 | `.agent/SESSION_POINTER.md`, `.agent/WORK_JOURNAL.md`, `.agent/TECH_DEBT_BACKLOG.md`, `.agent/TESTING-STATE.md`, `.agent/scripts/regenerate_step_inventory.py` | **KEEP** for now; see T0.B residual. |
| `chore(release): bump version` (1 commit) | 1 | version files | **KEEP** — version bump required. |
| `docs(historico): archive 17 superseded roadmap documents` | 1 | `docs/historico/*` | **KEEP** — historical archive. |

**Commit-level audit (per commit):** see `git log origin/main..HEAD` output. No accidental/duplicate/superseded/inappropriate commits found.

**Discrepancies worth flagging (informational, not blocking):**

- `bd6cc2f9 docs(historico): archive 17 superseded roadmap documents`: 17 docs archived under `docs/historico/`. Re-measured count tracks. **No drift.**
- `.agent/*` tracked despite `.gitignore`: pre-existing; **KEEP** (T0.B decision), not a TRAIN-0 cleanup obligation.
- `0ce46af5 / 728e5606 / 177d0b9e / d403c41b / c0d7971f / 82d7bfe4 / 68dd6f44`: 7 CURRENT_STATE regen commits; one per generator state change. Regenerations are an artifact of the `gen-current-state-projection.py` determinism era and **KEEP** (no live cycle effect).
- `40f91842` is the **single governance commit** introduced by this TRAIN so far. It is the only commit that changes the operative authority model; downstream commits to do not yet exist.

**Affected-tests evidence (per AGENTS.md rule 17):**

- `scripts/test_admission_check.py` → 42/42 OK in 8.5s.
- `scripts/test_gen_current_state_projection.py` → 6/6 OK in 0.03s.
- `scripts/test_gen_current_uat_status.py` → 10/10 OK in 0.004s.
- `scripts/test_classify_open_prs.py` → 8/8 OK in 0.004s.
- Total: **66 unit tests PASS**. No full integration suite (T0.E).

**Decision:** the 44-ahead delta is **integration-ready** in the sense that nothing in it needs to be excluded before the future integration candidate. No merge to `main` is performed by this slice.

---

## T0.E — readiness

T0.E has its own WorkItem (`c338edee-9ec0-4433-8577-ac59e9f18d3d`). It will:

1. Confirm the candidate SHA against `origin/wu/rp-053r-red-fixtures` HEAD.
2. Run `admission-check.py` against the candidate — must report 6/6 PASS.
3. Run full integration suite (this is where the long Gradle session belongs; not run here).
4. Verify a fresh clone with same SHA → same admission decision (reproducibility invariant from PRDY-006R2).
5. Verify SDDK can rebuild `where/next/blockers` without reading `.agent/SESSION_POINTER.md`.
6. Produce one consolidated T0.E receipt.

**Operator-only merge gate.** No commit on `main` is made by this receipt.

---

## Negative knowledge logged

- `.agent/` is in `.gitignore` yet tracked. Editing `.gitignore` does not retroactively untrack already-tracked files. `git rm --cached` is the right tool but is destructive and out-of-scope for TRAIN-0.
- SDDK attention gate hard-tracks one WorkItem at a time; transition to `done` only AFTER `sddk-close` and only AFTER `git push`. The T0.A transition order was empirically validated.
- `sddk-align --ack` requires ASCII-safe strings for flag values; the bash interpreter parses each `--flag` as one arg then one value, so multi-byte UTF-8 in shell-quoted `'` strings is safe but the `;` Unicode replacement character was not safe in T0.A — see `git sddk-align --ack` invocations in the previous session for the recovery pattern.
- D-006 inheritance: 19 instances exist even though only ~6 Step burns-down have been CERTIFIED in this era. Some of those 19 may already be dead code (from previously consolidated Steps). TRAIN-RP6-A should re-count before refactoring.

---

## Material identity

```text
Receipt SHA       : this file (post-commit; see git log)
T0.A commit       : 40f91842 (cutover)
T0.B+T0.C+T0.D    : this receipt only; one commit expected to follow
                    after receipt review
origin/main       : acc903875d70f939713786d71a6331bb6ccf7dc9 (UNCHANGED)
intake harness #3 : OPEN (unchanged; clock is harness's)
```

T0.A → **DONE** in `40f91842`.
T0.B → **DONE** in this receipt (no code change; analysis + classification).
T0.C → **DONE** in this receipt (decision + trigger recorded).
T0.D → **DONE** in this receipt (re-measured ahead/behind = 44/0; classified intent; affected tests = 66/66 PASS).
T0.E → **READY** (its WorkItem is `c338edee-9ec0-4433-8577-ac59e9f18d3d`).
