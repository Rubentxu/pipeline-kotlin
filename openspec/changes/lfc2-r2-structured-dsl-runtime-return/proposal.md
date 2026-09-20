# Change: lfc2-r2-structured-dsl-runtime-return

## Why

The Tier A queue (`STEP_REGISTRY_PLAN.md`) declares `core.pwd` BLOCKED under the
`STRUCTURED_DSL_RUNTIME_RETURN_GAP`. The gap frozen in
`docs/v2/07-uat/S2_A6_CORE_PWD_G7_STOP_BLOCKED_RECEIPT.md` is **horizontal**,
not `core.pwd`-specific: a Step executed by the durable runtime cannot return
its typed `O` into the Kotlin frame that built a `PipelineSpec` through the
**eager structured frontend** (`pipeline { stages { stage { steps { ... } } } }`).
It affects the whole runtime-returning family: `pwd()`, `pwd(tmp=true)`,
`readFile()`, `fileExists()`, and any future Step whose handler returns a typed
value the script body needs to branch on.

A design spike already exists on the branch
`cycle/lfc2-e1-r2-runtime-return` (commit `2227fa87` + renumber commit
`b7685b97`). The spike evaluates three designs:

- **A — CPS transformation** (rejected; contradicts ADR-0006, which already
  rejected CPS in 2024);
- **B — Suspend structured DSL** (RECOMMENDED; SPIKE-016 passed, LFC-2R R2
  shipped the same seam at generator level for `isUnix`);
- **C — Two-phase declarative reference/value binding** (rejected as primary;
  no typed branching).

The spike lives only on the branch and is numbered `0082`. `main` already owns
`ADR-0081` (BodyInvoker) and `ADR-0082` (LPR priority), so the spike must be
renumbered to the next free slot before merging. The next free ADR number on
`main` is **0093**.

This change brings the spike to `main` as **ADR-0093 — Structured DSL Runtime
Return**, with the renumber + an explicit "this is a design spike" status, so
the architectural decision is durable and reachable from the canonical docs
without re-fetching the historical branch. **No production code change in this
change.** The implementation slice (`stepValue`, `invokeTyped`, four consumers,
`Main.kt` form selector) is its own follow-up WU (`WU-LPR-087`).

## Outcomes

1. `docs/v2/04-adrs/ADR-0093-structured-dsl-runtime-return.md` exists in `main`,
   carrying the spike text from `cycle/lfc2-e1-r2-runtime-return@2227fa87` and
   `b7685b97` with the renumber `0081→0082→0093`.
2. `docs/v2/04-adrs/README.md` indexes ADR-0093.
3. `docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md` reflects the LFC-2R2 spike as a
   tracked artifact on the `core.pwd` row.
4. `docs/v2/05-roadmap/INITIATIVE_LPR_001.md` §Tier A.1 references ADR-0093.
5. `openspec/changes/lfc2-r2-structured-dsl-runtime-return/design.md` and
   `tasks.md` capture the spike's evaluation + the follow-up implementation
   work-units.

## Non-goals

- No production code change. This change is docs only.
- No authority flip for `core.pwd` (counters stay 6/6/6 — G5 LEGACY_REMOVED
  ✅, G6 ✅, G7 ❌ BLOCKED, G8 ❌ not attempted).
- No `pipeline {}` body rewrite. That is implementation work in WU-LPR-087.
- No new `StepSpec` subtype; the suspend frontend lowers to the same canonical
  representations.
- No SPIKE-016 re-run; its evidence is in-tree and binding.

## Reference evidence (in-tree, not re-derived)

- `docs/v2/07-uat/S2_A6_CORE_PWD_G7_STOP_BLOCKED_RECEIPT.md` — the blocker,
  raw archived JSONs, diagnosis.
- ADR-0006 — durable replay INSTEAD OF CPS (already rejected CPS once, 2024).
- ADR-0065 — D1: declarative discovery vs scripted execution; D2: durability is
  deterministic replay, NOT Kotlin continuation persistence.
- SPIKE-016 — passed: suspend scripted bodies replay durably without
  serializing continuations.
- `docs/v2/07-uat/LFC2R_R2_ISUNIX_SCRIPTED_RUNTIME_CONSUMER.md` — generator-
  level suspend runtime-return proven end-to-end (`core.isUnix`, R2 receipt).
- ADR-0084 — Honest DSL runtime values (the policy basis for the spike).

## Strict Certification Law

This change carries NO Step state mutation; it carries only an ADR (a
**decision**) and a reference update. No `CERTIFIED` / `IMPLEMENTED_UNCERTIFIED`
/ `DONE/PASS` label is produced for any Step by this change. The strict
certification law is preserved as-is. `core.pwd` remains
**BLOCKED (STRUCTURED_DSL_RUNTIME_RETURN_GAP)** until WU-LPR-087 implements
the spike and re-runs the G7 canary.
