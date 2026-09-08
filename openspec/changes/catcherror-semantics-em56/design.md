# Design: catchError / unstable semantics (EM-5/EM-6)

Anchored in fresh real ground-truth run + code trace at HEAD `f13d02a2`.

## Evidence anchors
- Linearization: `DslCompiledPipelineCompiler.rewriteWorkflowControl` (catchError → CatchErrorEntered,
  inner scope, CatchErrorTriggered marker).
- Marker publication: `CanonicalEmitEventNodeDispatcher` publishes `CatchErrorTriggered` unconditionally
  when the marker step runs.
- Overlay fold: `CanonicalDurableRunCoordinator.continuation()` (CatchErrorOverlay: FAILURE→Abort,
  SUCCESS→Continue, else→ContinueUnstable) + `run()` returns immediately on Abort.
- No StageFinished emitted by the canonical `run()` (only RunStarted/RunFinished).

## Design decision D1 — terminal inner-scope outcome must gate the trigger
Current marker is unconditional. Introduce a typed decision at the CatchErrorTriggered boundary:
publish `CatchErrorTriggered` only when the enclosing overlay's inner scope produced a real failure
(`StepOutcome.Failure`) that is being suppressed/re-thrown; do NOT publish when the inner terminal was
`StepOutcome.Unstable` from `unstable()` (ERR-S-008) or plain Success.
Track the inner-scope terminal outcome in the coordinator as the overlay pops (no cross-step shell
exit-code inference).

## Design decision D2 — FAILURE re-throw records the catch then aborts
On `CatchErrorOverlay(buildResult==FAILURE)` fold of a real inner failure:
1. publish `CatchErrorTriggered(buildResult=FAILURE, stageResult=FAILURE)` (ERR-S-002),
2. then Abort the run (existing FAILURE semantics preserved: exit 1, sibling `after-catch` not run).
Do this inside the fold, not by relying on the exit-marker step which is skipped on abort.

## Design decision D3 — nested overlay re-throw is LIFO
ERR-S-007: inner FAILURE should NOT abort the whole run when an enclosing `CatchErrorOverlay` exists.
Resolve the failure against the overlay chain outermost-first-after-innermost: the innermost
FAILURE overlay records+re-throws to the next enclosing overlay; the enclosing default (UNSTABLE)
overlay catches and downgrades to ContinueUnstable. Only when no enclosing overlay exists (or the
outermost is also FAILURE) does the run abort. Continuation must consult the full stack, not just
`peek()`.

## Design decision D4 — StageFinished at the stage boundary
The canonical coordinator must emit `StageFinished` (outcome `success`/`unstable`) when each stage's
linear steps fold, mirroring `PipelineRun` stage observability. ERR-S-004 requires outcome
`unstable` StageFinished after an in-stage `unstable()`. Preserve RunStarted/RunFinished bookends and
per-step events; add the missing stage-level bookend (per-step observability in AGENTS step semantics).

## Open question (verify during apply)
Exact site of inner-scope terminal tracking: whether to thread it on `ContextOverlay.CatchErrorOverlay`
(a mutable-ish terminal field on pop) or via the coordinator fold returning a richer continuation.
Constraint: no new `DomainEvent` subtypes, no `ContextOverlay.Credentials` changes, no IR secret
leaks. Reconcile with ADR-0054 §D5/D6 (warnError forced UNSTABLE; event payload contracts) and
UAT_JENKINS_EXECUTION_PARITY.

## Migration path
1. D1 + D2 together (they are one fold change): gate + record-on-rethrow. Verify ERR-S-002/008.
2. D3 nested LIFO. Verify ERR-S-007 (+ ERR-S-001 nested-catch not present, but ERR-S-007).
3. D4 StageFinished. Verify ERR-S-004 + full ErrorHandlingTest + coordinator suites green.
Each step keeps ERR-S-001/003/006 green before moving on.

## Design refinement D5 — publication must move into the fold-walk, not the markers
Traced consequence (2026-09-08, apply prep): the linear IR markers cannot remain the sole
publisher of CatchErrorTriggered.
- ERR-S-007 nests: outer-enter, inner-enter, body(sh exit 1), inner-trigger, outer-trigger. The body
  Failure must pass through the inner FAILURE overlay (re-throw) to the outer UNSTABLE overlay, which
  suppresses; ≥2 triggers (inner FAILURE + outer UNSTABLE). A single fold over one `StepOutcome` cannot
  produce two marker-published events cleanly, and the current Abort returns too early.
- ERR-S-008: the marker currently publishes after a non-failing body (unstable is lifted past the
  trigger), so it cannot distinguish "real inner failure" from "unstable/success".
Therefore publication belongs in the run() fold: on a real `StepOutcome.Failure`, walk the
`ContextStack` from innermost outward, publishing one CatchErrorTriggered per enclosing
CatchErrorOverlay (its own buildResult/stageResult/message), stopping at the first overlay that
suppresses (FAILURE-overlays re-throw upward; SUCCESS/UNSTABLE overlay → Continue/ContinueUnstable), or
aborting when none remains. The IR CatchErrorTriggered markers then become pop-only and MUST NOT
separately publish (avoid double-count). The overlay currently stores only buildResult+enteredAt; the
fold needs stageResult+message per scope — resolve by carrying them on the CatchErrorOverlay (typed
fields) rather than parsing IR markers at runtime. This re-shapes D1-D4 and keeps ERR-S-001 (single
suppress) emitting at the fold.
Open: confirm whether `StepOutcome.Unstable` from `unstable()` must be excluded from the walk (it is
not a Failure, so it never enters the walk — resolves ERR-S-008 without a separate gate).
