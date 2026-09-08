# Tasks: catcherror-semantics-em56 (EM-5/EM-6)

Evidence: `ErrorHandlingTest` ERR-S-002/003/006/008 (red, roadmap-deferred, pre-existing).
Harness: PipelineRule (SPIKE-017, passed). Base: `a8a12e02`.
> Production coordinator change — follow design/ADR for the catchError re-throw + nesting
> contract before apply (per AGENTS v2 prime directive).

## Design
- D: trace the catchError overlay continuation in `CanonicalDurableRunCoordinator.dispatch()`
  + `StepExecutionBoundary`; record ADR entry for: re-throw on `buildResult==FAILURE` (emit
  CatchErrorTriggered then propagate Failure), LIFO nested-overlay resolution (inner wins), and
  `unstable`/StageMarkedUnstable not being a catchable failure. Reconcile ADR-0054 +
  UAT_JENKINS_EXECUTION_PARITY.

## T1 — ERR-S-002 re-throw (buildResult FAILURE)
- Emit `CatchErrorTriggered` on the FAILURE path and propagate the Failure (do not suppress).
- Regression via PipelineRule: buildResult FAILURE inner `sh exit 1` => RunOutcome.Failure,
  CatchErrorTriggered present, sibling echo not run.

## T2 — ERR-S-003 warnError UNSTABLE
- warnError inner failure => StageMarkedUnstable present and StageFinished present; pipeline
  exits 0 (UNSTABLE not abort).

## T3 — ERR-S-006 nested catchError (outer catches inner)
- Nested overlays resolve LIFO; outer catchError re-catches inner outcome; pipeline continues.

## T4 — ERR-S-008 unstable override
- `unstable` inside catchError must NOT fire CatchErrorTriggered (no failure to catch).

## Verify
- L1/L2 via PipelineRule (fast) per case + ERR-S real-process parity.
- ERR-S-001 (default UNSTABLE suppress) and ERR-S-007 nested-catch continue green.
