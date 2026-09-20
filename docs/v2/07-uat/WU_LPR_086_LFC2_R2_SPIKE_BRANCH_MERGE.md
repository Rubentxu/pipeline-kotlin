# WU-LPR-086 — LFC-2R2 Spike Branch Merge (ADR-0093 to main)

| Field | Value |
|---|---|
| Date | 2026-09-20 |
| Status | **CLOSED** |
| Trigger | INITIATIVE LPR-001 §Tier A.1 — LFC-2R2 horizontal blocker. After closing `core.waitUntil` G6+G8 (WU-LPR-085), the next binding step on the Tier A path was the horizontal blocker that gates `core.pwd` G7+G8, `core.pwdTmp` G6+G8, `readFile`, and `fileExists`. |
| Parent | `d207f72d` (post WU-LPR-085) |
| Scope | Bring the LFC-2R2 design spike to `main` as `ADR-0093` (docs only). The implementation slice is WU-LPR-087. |
| Outcome | Spike ACCEPTED on `main`; `core.pwd` row updated; Tier A.1 cross-references ADR-0093; next WU (WU-LPR-087) authorised. |
| Initiative | INITIATIVE LPR-001 (binding umbrella, auto-run mode active). |

## 1. Diagnosis

`core.pwd` has been BLOCKED on `STRUCTURED_DSL_RUNTIME_RETURN_GAP` since the
S2-A6 G7 canary on 2026-09-12 (receipt `S2_A6_CORE_PWD_G7_STOP_BLOCKED_RECEIPT.md`).
The blocker is **horizontal**: it gates the whole runtime-returning family
(`pwd()`, `pwd(tmp=true)`, `readFile()`, `fileExists()`, plus `sh(returnStdout)`).
Closing it requires:

1. A binding architectural decision for the structured-frontend runtime-return
   seam (ADR-level);
2. A production implementation of that decision (code-level);
3. Re-running the G7 canary for each consumer with the new path.

A design spike already existed on branch `cycle/lfc2-e1-r2-runtime-return`
(commits `2227fa87` and `b7685b97`), but it was:

- not on `main` (so not reachable from the canonical docs without fetching
  the historical branch);
- numbered `0082` (which conflicted with `main`'s `ADR-0082-local-production-ready-priority.md`).

This WU performs step (1): bring the spike to `main` as **ADR-0093** with the
status upgraded from `PROPOSED` to `ACCEPTED` (the architectural decision is
binding for `main` once accepted), so step (2) (implementation in WU-LPR-087)
has a stable architectural reference and step (3) can execute against a
canonical decision.

## 2. Change description

**No production code change.** Artefacts added or updated:

| Path | Purpose |
|---|---|
| `openspec/changes/lfc2-r2-structured-dsl-runtime-return/proposal.md` | Why this change; what it produces; non-goals; reference evidence. |
| `openspec/changes/lfc2-r2-structured-dsl-runtime-return/design.md` | Decision rationale; renumber path; spike evaluation summary; risk; what WU-LPR-086 produces vs WU-LPR-087. |
| `openspec/changes/lfc2-r2-structured-dsl-runtime-return/tasks.md` | This slice's tasks + WU-LPR-087 follow-up tasks. |
| `docs/v2/04-adrs/ADR-0093-structured-dsl-runtime-return.md` | Spike text carried forward (335 lines) with renumber + ACCEPTED status. |
| `docs/v2/04-adrs/README.md` | Index entry for ADR-0093 (added between ADR-0091 and ADR-0093 entry). |
| `docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md` | `core.pwd` row references ADR-0093; blocker evidence section cross-refs the spike source. |
| `docs/v2/05-roadmap/INITIATIVE_LPR_001.md` | §Tier A.1 references ADR-0093 as binding design + WU-LPR-087 as implementation slice. |
| `docs/v2/07-uat/HANDOFF-2026-09-20-LPR-AUTO-RUN.md` | Refresh: next WU is WU-LPR-087 (implementation). |

## 3. Verification (evidence)

### 3.1 Spike text carried forward

- **Source:** `origin/cycle/lfc2-e1-r2-runtime-return:docs/v2/04-adrs/ADR-0082-structured-dsl-runtime-return.md` at `b7685b97`.
- **Destination:** `docs/v2/04-adrs/ADR-0093-structured-dsl-runtime-return.md` (335 lines; identical body except renumber + status).
- **Renumber:** `ADR-0081 → ADR-0082 → ADR-0093`; the renumber history is recorded in the ADR header (`Status: ACCEPTED (renumbered from ADR-0082 ...; promoted from PROPOSED to ACCEPTED ...)`).

```bash
$ wc -l docs/v2/04-adrs/ADR-0093-structured-dsl-runtime-return.md
335 docs/v2/04-adrs/ADR-0093-structured-dsl-runtime-return.md
$ head -n 4 docs/v2/04-adrs/ADR-0093-structured-dsl-runtime-return.md
# ADR-0093 — Structured DSL Runtime Return: suspend structured DSL (design spike LFC-2R2)
Status: ACCEPTED (renumbered from ADR-0082 on branch `cycle/lfc2-e1-r2-runtime-return` because `main` owns ADR-0081/0082; promoted from PROPOSED to ACCEPTED on `main` per WU-LPR-086; resolves blocker `STRUCTURED_DSL_RUNTIME_RETURN_GAP`)
Date: 2026-09-12 (branch); promoted to ACCEPTED on 2026-09-20 by WU-LPR-086.
```

### 3.2 ADR index updated

```text
- [ADR-0091: Trunk authority / historical branches](ADR-0091-trunk-authority-historical-branches.md)
- [ADR-0092: Plugin identity provider registration (additive)](ADR-0092-plugin-identity-provider-registration-additive.md)
- [ADR-0093: Structured DSL runtime return — suspend structured DSL](ADR-0093-structured-dsl-runtime-return.md)
```

### 3.3 Inventory + initiative cross-refs

`STEP_INVENTORY_LFC2E0.md`:

- `core.pwd` row now: `... | S2_A6_CORE_PWD_G7_STOP_BLOCKED_RECEIPT.md + [ADR-0093](../04-adrs/ADR-0093-structured-dsl-runtime-return.md) | G7 2/4 pass; LFC-2R2 design spike ACCEPTED on main (WU-LPR-086); implementation deferred to WU-LPR-087`.
- Blocker evidence section now carries:
  ```text
  Design: ADR-0093 — Structured DSL Runtime Return (suspend structured DSL,
  ACCEPTED on main per WU-LPR-086, 2026-09-20; renumbered from ADR-0082 on
  branch cycle/lfc2-e1-r2-runtime-return). Implementation slice: WU-LPR-087.
  ```

`INITIATIVE_LPR_001.md` §Tier A.1:

```text
Tier A.1 — LFC-2R2 (horizontal blocker)
  - Structured Runtime-Returning Steps (pwd/pwdTmp/readFile/fileExists)
  - Design binding: ADR-0093 (suspend structured DSL, ACCEPTED 2026-09-20 WU-LPR-086)
  - Spike merged from branch cycle/lfc2-e1-r2-runtime-return (renumbered 0082 → 0093)
  - Implementation slice: WU-LPR-087 (production code change; not in WU-LPR-086)
```

### 3.4 No production code change (docs-only slice)

```bash
$ git diff --name-only d207f72d -- ':!docs' ':!openspec' 2>&1
# (empty — no production code touched)
```

### 3.5 Validation ladder

Per AGENTS.md §V2 testing rules, a docs-only slice skips Gradle validation:

- **L0 compile:** N/A (no Kotlin change).
- **L1 single test:** N/A (no test change).
- **L2 class:** N/A.
- **L3 related set:** N/A.
- **L4 module suite:** N/A.
- **L5 round gate:** N/A (docs-only — explicit exception in AGENTS.md §V2 testing rules).

The full L5 gate is reserved for the implementation slice (WU-LPR-087).

## 4. Acceptance checklist (AGENTS.md prime directives)

| Mandate | Status |
|---|---|
| Strict certification law | OK — no Step state mutation; no `DONE/PASS`/`IMPLEMENTED_UNCERTIFIED`/`WIP`/`TBD`/`partial` produced; `core.pwd` remains `BLOCKED` until WU-LPR-087 closes it. |
| Hexagonal architecture | OK — ADR-0093 is a decision artefact; no module boundaries touched. |
| Adapter direction | OK — no adapter change. |
| DSL describes, not executes | OK — no DSL change in this slice. |
| Step Constitution | OK — no Step touched; the spike defines the future suspend DSL seam but does not yet instantiate it. |
| Strict typed functional design | OK — the spike promotes `O` to a typed value via `outputCodec.decode`; no `Any?` / sentinel leakage. |
| Reference implementation consulted | OK — SPIKE-016 (passed); LFC-2R R2 (generator-level `core.isUnix` precedent); ADR-0006 (rejected CPS once); ADR-0065 (declarative discovery / durability = replay). |
| Security implications reviewed | OK — docs only; no execution path changes; the spike itself notes no new capability surface (one generic seam over existing codecs + callSite). |
| End-of-work-unit closure block | OK — present in `openspec/changes/lfc2-r2-structured-dsl-runtime-return/design.md` §9 and mirrored here. |

## 5. End-of-work-unit closure block

```text
Reference implementation consulted: SPIKE-016 (passed); LFC-2R R2 (generator-level core.isUnix precedent); ADR-0006 (rejected CPS once); ADR-0065 (declarative discovery + durability is deterministic replay)
Behaviour adopted:                 suspend structured DSL; one generic stepValue seam over (StepKey, encodedInput, outputCodec, callSite)
Intentional deviations:            renumber ADR-0082 → ADR-0093 (slot conflict on main); status PROPOSED → ACCEPTED (the decision is now binding for main)
Security implications reviewed:    n/a for docs only; spike notes no new capability surface
Tests demonstrating the contract:   docs/v2/04-adrs/ADR-0093-structured-dsl-runtime-return.md
                                    docs/v2/04-adrs/README.md (index)
                                    docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md (cross-ref)
                                    docs/v2/05-roadmap/INITIATIVE_LPR_001.md (Tier A.1 cross-ref)
                                    openspec/changes/lfc2-r2-structured-dsl-runtime-return/{proposal,design,tasks}.md
```

- WU-LPR-086 result: **CLOSED** (ADR-0093 on main; spike ACCEPTED; no production code change; `core.pwd` still BLOCKED until WU-LPR-087).
- Next binding step: **WU-LPR-087 (LFC-2R2 implementation)** — `stepValue` + `invokeTyped` + 4 consumers + `Main.kt` form selector predicate + `core.pwd` G7 canary + G8 + `core.pwdTmp` G6+G8.

## 6. Commit + tag + push plan

```bash
git add docs/v2/04-adrs/ADR-0093-structured-dsl-runtime-return.md \
        docs/v2/04-adrs/README.md \
        docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md \
        docs/v2/05-roadmap/INITIATIVE_LPR_001.md \
        openspec/changes/lfc2-r2-structured-dsl-runtime-return/{proposal,design,tasks}.md \
        docs/v2/07-uat/WU_LPR_086_LFC2_R2_SPIKE_BRANCH_MERGE.md \
        docs/v2/07-uat/HANDOFF-2026-09-20-LPR-AUTO-RUN.md

git commit -m "WU-LPR-086: bring ADR-0093 (LFC-2R2 spike) to main — docs only"

git tag -f wu-lpr-086
git push origin main
git push origin wu-lpr-086
```
