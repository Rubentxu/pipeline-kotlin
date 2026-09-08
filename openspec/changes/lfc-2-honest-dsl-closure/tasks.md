# Tasks: lfc-2-honest-dsl-closure (LFC-2)

Evidence: HEAD 65f75fcc (EM-5/6 catchError closed). Confirmed pre-existing DSL failures on clean
Part B: UatDsl001 (full-grammar CLI exit 1 + fixture timeline), UatEvt001 (G3 naming), UatDsl003
(parallel G2 compiler), ERR-S-004 (stage bookends folded). Base = clean Part B (65f75fcc).

## T0 — Triage + itemize LFC-2
- Root-cause each confirmed red DSL test; classify fix vs scope-out.
- Write the LFC-2 itemized list + exit gate into `docs/v2/05-roadmap` (representative Jenkins
  fixtures compile to expected IR; no fake-return DSL fitness).
- Inventory the LFC-2 gate items: @DslMarker narrow receivers, closed StageBody, .pipeline.kts
  @KotlinScript, incomplete steps (post/when/waitUntil/pwd/isUnix...), git/scmGit duplicate
  construction, shell dollar handling, durable script {} boundary. Mark each present/absent.

## T1 — parallel DSL surface (G2)
- Open `parallel` + sibling steps in the DSL compiler (currently rejected). Verify UatDsl003 green.

## T2 — step-naming reconciliation (G3, UatEvt001)
- Align the timeline/event tests to the `<stage>/<type>-<index>` contract the coordinator emits.
  Verify UatEvt001 green.

## T3 — stage bookends rebaseline (ERR-S-004 folded)
- Land StageStarted/StageFinished in the coordinator; rebaseline the DSL/corpus event timelines that
  the bookends legitimately shift. Verify ERR-S-004 + UatDsl001 timelines green.

## T4 — remaining LFC-2 gate items
- Steps the gate names that are incomplete/fake-return (post/when/waitUntil/pwd/isUnix, ...);
- shell dollar handling / source rewriting; durable `script {}` boundary.

## Verify
- UatDsl001/003, UatEvt001, ErrorHandlingTest (ERR-S-004) green.
- Gate: representative Jenkins fixtures compile to expected IR; no fake-return DSL fitness violation.
- Coordinator/EM suites (EM-5/6) stay green — no unjustified regression.
