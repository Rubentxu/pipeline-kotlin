# Proposal: catchError workflow-control semantics (EM-5/EM-6)

## Intent
`ErrorHandlingTest` ERR-S-002/003/006/008 remain red on a roadmap-deferred, pre-existing gap:
the canonical coordinator leaves `catchError`/`warnError` on the legacy linear path and does not
fully implement the Jenkins semantics these tests encode. With the `PipelineRule` in-process harness
(SPIKE-017) now available, these semantics can be specified and verified fast and deterministically
instead of via 300s real-process UATs. This proposal scopes the milestone and its exit criteria.

## Scope
### In Scope
- ERR-S-002: `catchError(buildResult = "FAILURE")` must emit `CatchErrorTriggered` and **re-throw**
  (propagate the failure) — currently the trigger is not emitted on the FAILURE path.
- ERR-S-003: `warnError` forces UNSTABLE on inner failure; `StageFinished` must be present.
- ERR-S-006: nested `catchError` — outer catches inner (inner wins / outer re-catches) semantics.
- ERR-S-008: `unstable` inside `catchError` must NOT fire `CatchErrorTriggered` (override catch).
- Regression coverage via `PipelineRule` + the existing coordinator overlay model.
### Out of Scope
- Durable replay/kill of catchError scopes (separate).
- warnError/error projection beyond the above cases.

## Capabilities
### New Capabilities
- None (behavior lives under the canonical workflow-control capability).
### Modified Capabilities
- `workflow-control` (new spec under `openspec/specs/`): catchError/warnError re-throw, nesting, and
  unstable-override semantics per the ERR-S contract. (The openspec capability name to be confirmed
  against `openspec/specs/` during spec phase.)

## Approach
The coordinator already models catchError via `ContextOverlay.CatchErrorOverlay` and the linear IR
(emit-event `CatchErrorEntered`/`CatchErrorTriggered` + shell segments). The missing semantics are:
- On inner `Failure` with overlay `buildResult == FAILURE`: emit the trigger and propagate a
  `Failure` (not suppress).
- Nested overlays: inner overlay resolves before outer (LIFO); ERR-S-006 outer catches inner outcome.
- `StageMarkedUnstable` from an inner `unstable` must not be treated as a catchable failure.
Trace the overlay continuation in `dispatch()` + `StepExecutionBoundary` and align each ERR-S case.

## Affected Areas
| Area | Impact | Description |
|------|--------|-------------|
| `CanonicalDurableRunCoordinator.kt` dispatch/catchError overlay | Modified | re-throw + nesting + unstable-override |
| `ErrorHandlingTest` ERR-S-002/003/006/008 | Modified | go green per agreed contract |
| `PipelineRule` harness tests | New | fast regression for each case |

## Risks
| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Changing catchError alters existing suppress behavior (ERR-S-001) | Med | Keep default UNSTABLE suppress; only FAILURE path re-throws |
| Overlay LIFO interaction with sibling steps | Med | PipelineRule fast verify per case |

## Rollback Plan
Coordinator-only change scoped to the catchError overlay branch; existing suppress semantics kept.
Revert the specific overlay hunk; harness tests isolate behavior.

## Dependencies
- PipelineRule harness (passed) for fast coverage.
- Design/ADR for the re-throw + nesting contract; reconcile with ADR-0054 / UAT_JENKINS_EXECUTION_PARITY.

## Success Criteria
- [ ] ERR-S-002/003/006/008 green via PipelineRule (fast) and real-process parity.
- [ ] ERR-S-001 suppress (default UNSTABLE) and ERR-S-007 nested-catch continue green.
- [ ] No change to stable, non-catchError coordinator behavior.
