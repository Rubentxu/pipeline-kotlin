# Proposal: catchError / unstable workflow-control semantics (EM-5/EM-6)

## Intent
Four `ErrorHandlingTest` scenarios remain red on a roadmap-deferred, pre-existing gap in the
canonical coordinator's catchError/warnError/unstable semantics. Fresh ground-truth run (2026-09-08,
7 tests, 4 failed, ~39 s) plus code trace of the compiler linearization and coordinator fold define
the real scope. This proposal scopes the milestone and its exit criteria.

## Ground truth (fresh run, current HEAD)
Failing (4):
- ERR-S-002 `catchError(buildResult="FAILURE") re-throws`: `CatchErrorTriggered` domain event NOT
  emitted. The abort (exit 1) works; the catch-then-rethrow is not recorded as an event.
- ERR-S-004 `unstable(message) marks stage`: `StageFinished` absent for an unstable stage outcome.
- ERR-S-007 `nested catchError inner wins`: pipeline exits != 0 — the outer default catchError does
  not re-catch the inner FAILURE re-throw and downgrade it to UNSTABLE.
- ERR-S-008 `unstable inside catchError overrides`: `CatchErrorTriggered` fires when it should not
  (1 emitted), because `unstable()` is not a failure to catch.

Passing (3): ERR-S-001 (default catch suppresses + emits CatchErrorTriggered UNSTABLE), ERR-S-003
(warnError forces UNSTABLE + StageMarkedUnstable), ERR-S-006 (pipeline unstable exits 0).

> Earlier proposals mislabeled these cases from memory (stale XML had shifted numbering). This is the
> corrected scope anchored in a fresh real run.

## Root-cause (code trace)
`DslCompiledPipelineCompiler.rewriteWorkflowControl` linearizes catchError to
[CatchErrorEntered, inner scope (set +e shell wrapper / nested rewrites), CatchErrorTriggered marker].
`CanonicalDurableRunCoordinator.dispatch()` pushes/pops the `CatchErrorOverlay`; the exit marker is
dispatched by `CanonicalEmitEventNodeDispatcher`, which publishes a `CatchErrorTriggered` DomainEvent
**unconditionally** whenever the marker step runs. The coordinator `run()` fold
(`StepOutcome.continuation(contextStack)`) aborts immediately on `CatchErrorOverlay(buildResult==FAILURE)`.

- ERR-S-002: the FAILURE fold returns before the exit-marker step executes, so no event is recorded.
- ERR-S-008: the marker publishes even when the inner scope only produced a `StageMarkedUnstable`
  (`StepOutcome.Unstable`), i.e. no failure to catch.
- ERR-S-007: the inner FAILURE fold aborts the whole run; it must instead re-throw to the next
  enclosing `CatchErrorOverlay` (LIFO), which downgrades to UNSTABLE at the outermost default scope.
- ERR-S-004: the canonical coordinator emits no `StageFinished` for stage outcomes (only
  RunStarted/RunFinished in `run()`); the stage-level `unstable` outcome leaves no `StageFinished`.

## Scope
### In Scope
- CatchErrorTriggered emission on the FAILURE re-throw fold (record catch-then-rethrow as an event).
- Gate the CatchErrorTriggered marker emission on the inner scope actually failing (no event when only
  `unstable` occurred).
- Nested overlay re-throw resolution (LIFO): inner FAILURE re-throws to the enclosing overlay;
  outermost FAILURE still aborts.
- StageFinished emission for stage outcomes in the canonical coordinator (stable + unstable).
- Regression coverage via the real `ErrorHandlingTest` (fast, ~39 s) + coordinator unit tests.
### Out of Scope
- Durable replay/kill of catchError scopes (separate).
- warnError/error projection beyond the above.

## Approach
Introduce an explicit, typed notion of "inner scope terminal outcome" at the CatchErrorTriggered
boundary so the coordinator can decide catch vs re-throw vs no-op precisely, instead of the current
unconditional marker publication + immediate abort. Resolve nested failures LIFO against the
`ContextStack` overlay chain. Emit StageFinished at the stage boundary in the coordinator fold.

## Affected Areas
| Area | Impact |
|------|--------|
| `DslCompiledPipelineCompiler.rewriteWorkflowControl` | Modified (trigger gating input) |
| `CanonicalEmitEventNodeDispatcher` / trigger publication | Modified (conditional on real failure) |
| `CanonicalDurableRunCoordinator` fold + nested continuation | Modified (LIFO re-throw, emit on rethrow, StageFinished) |
| `ErrorHandlingTest` ERR-S-002/004/007/008 | Green per contract |
| Coordinator unit tests | New per case |

## Risks
| Risk | Mitigation |
|------|------------|
| Changing FAILURE rethrow alters ERR-S-001/003/006 behavior | Keep default-UNSTABLE suppress unchanged; only FAILURE fold records+rethrows |
| Nested LIFO interacts with sibling/scope leak | Coordinator unit tests; keep scope-leak invariant |
| StageFinished emission shifts other suites | Verify UatLocal stage suites + coordinator tests stay green |

## Dependencies
- Ground-truth harness (real `ErrorHandlingTest`, ~39 s) for fast per-case verification.
- Design/ADR in `openspec/changes/catcherror-semantics-em56/design.md`; reconcile ADR-0054 §D5/D6 +
  UAT_JENKINS_EXECUTION_PARITY + per-step observability (AGENTS step semantics).

## Success Criteria
- [x] ERR-S-002/007/008 green via real `ErrorHandlingTest` (landed 2026-09-08, commit 6e9bd4ac).
- [x] ERR-S-001/003/006 (suppress, warnError, pipeline unstable) remain green.
- [x] No new DomainEvent subtype, no weakened assertions (the existing marker→dispatcher contract
  test evolved to the new pop-only contract, per the "tests evolve with legitimate changes" rule).
- [ ] ERR-S-004 (stage observability) — split into a separate change with corpus/event-baseline
  rebaseline; see `tasks.md` "Split — ERR-S-004 stage bookends".
