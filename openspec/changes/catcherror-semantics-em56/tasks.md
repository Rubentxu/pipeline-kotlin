# Tasks: catcherror-semantics-em56 (EM-5/EM-6)

Evidence: fresh real `ErrorHandlingTest` run (HEAD `f13d02a2`) — ERR-S-002/004/007/008 red,
ERR-S-001/003/006 green. Design: `design.md` (D1-D4). Apply is gated per AGENTS V2; verify each step
keeps ERR-S-001/003/006 green before advancing. Fast gate: real `ErrorHandlingTest` (~39 s).

## T1 — Gate the CatchErrorTriggered marker on a real inner failure (D1)
- Track the inner-scope terminal `StepOutcome` at the CatchErrorTriggered boundary; publish the
  `CatchErrorTriggered` domain event only when the inner scope actually failed.
- Fixes ERR-S-008 (no event when only `unstable()` fired). Verify ERR-S-008 green, ERR-S-001 stays
  green (real failure still emits).

## T2 — FAILURE re-throw records the catch then aborts (D2)
- On `CatchErrorOverlay(buildResult==FAILURE)` fold of a real inner failure, publish
  `CatchErrorTriggered(buildResult=FAILURE, stageResult=FAILURE)` before aborting.
- Fixes ERR-S-002. Verify ERR-S-002 green (event present + exit 1 + no after-catch echo).

## T3 — Nested overlay re-throw resolves LIFO (D3)
- Inner FAILURE re-throws to the next enclosing overlay; enclosing default (UNSTABLE) overlay catches
  and downgrades to ContinueUnstable; abort only at the outermost FAILURE / no overlay.
- Fixes ERR-S-007. Verify ERR-S-007 green (exit 0, >=2 CatchErrorTriggered, after-nested echo runs).

## T4 — StageFinished at the stage boundary (D4)
- Emit `StageFinished` (outcome success/unstable) as each stage's linear steps fold in the canonical
  coordinator.
- Fixes ERR-S-004. Verify ERR-S-004 green + no regression to ERR-S-001/003/006/007 + coordinator
  scope/event suites.

## Verify
- After T1-T3: run `ErrorHandlingTest` fully; all ERR-S-001..008 green.
- Coordinator unit suites (`CanonicalDurableRunCoordinatorTest`, `CanonicalCoordinatorScopeStackTest`,
  `CanonicalEmitEventNodeDispatcherTest`, `CanonicalCoordinatorScopeStackTest`) stay green.
- Compiler suite (`DslCompiledPipelineCompilerTest`) stays green.
- No new DomainEvent subtype, no `ContextOverlay.Credentials` change, no IR secret leak.
