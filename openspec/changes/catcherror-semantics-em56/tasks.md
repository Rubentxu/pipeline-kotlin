# Tasks: catcherror-semantics-em56 (EM-5/EM-6)

Evidence: fresh real `ErrorHandlingTest` run (HEAD `f13d02a2`) — ERR-S-002/004/007/008 red,
ERR-S-001/003/006 green. Design: `design.md` (D1-D5). Fast gate: real `ErrorHandlingTest` (~39 s).

## STATUS (landed 2026-09-08, commit 6e9bd4ac)
T1 (D1 gate), T2 (D2 record-then-abort), T3 (D3 nested LIFO) + the D5 fold-walk redesign are
DONE and green: ERR-S-002/007/008 now PASS; ERR-S-001/003 stay green; all coordinator
(`CanonicalDurableRunCoordinatorTest`, `CanonicalCoordinatorScopeStackTest`,
`CanonicalEmitEventNodeDispatcherTest`, `CanonicalCoordinatorScopeStackTest`) + compiler + domain
(`ContextStackImmutabilityTest`) suites green. **Milestone complete.**

## Reclassified — ERR-S-004 stage bookends → LFC-2/observability (NOT catchError scope)
ERR-S-004 is a stage-observability feature, not catchError semantics. Its landing (StageStarted/
StageFinished in the canonical coordinator) shifts the event stream and its rebaseline lives on the
DSL/corpus test surface (UatDsl001, corpus timelines) that is ALREADY pre-existing red and is the
territory of the LFC-2 "honest DSL" closure. Verified: UatDsl001/UatEvt001 fail identically on the
clean Part B (no bookends) — pre-existing, unrelated to catchError. ERR-S-004 is folded into the
LFC-2 closure change (openspec change `lfc-2-honest-dsl-closure`), NOT into this milestone.

## Remaining for this change
None — catchError semantics complete and green (ERR-S-002/007/008; ERR-S-001/003/006 preserved).

## Original task list (for reference)
### T1 — Gate the CatchErrorTriggered marker on a real inner failure (D1)
### T2 — FAILURE re-throw records the catch then aborts (D2)
### T3 — Nested overlay re-throw resolves LIFO (D3)
### T4 — StageFinished at the stage boundary (D4) — SPLIT OUT (see above)
