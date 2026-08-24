# ADR-0040: Reconciler-driven Resume Orchestration

- **Status:** Accepted for M3-R4.4
- **Date:** 2026-08-24
- **Decision owners:** Pipeline Kotlin maintainers
- **M3-R4.4 Implementation:** T-03 (C3)

## Context

`BranchReconciler.reconcileRunningOperations` (`BranchReconciler.kt:87`) is declared as a `suspend` function. The durable walk chain — `PipelineOrchestrator.run()`, `walkPipelineSpecDurable`, `walkParallelFrame`, `walkBranchDurable`, `executeDurableStepImpl` — was declared as plain `fun`. Wiring the reconciler into the resume path requires invoking a `suspend` function from within the walk chain.

Additionally, `ReplayCursorStore` lacks a `getStageIndex(branchOpId)` API (it only has `load(runId)` returning run-level cursor). This creates a branch-level cursor gap documented as U-3 in M3-R4.4.

## Decision

Propagate `suspend` up the entire durable walk chain. Mark `PipelineOrchestrator.run()`, `walkPipelineSpecDurable`, `walkParallelFrame`, `walkBranchDurable`, and `executeDurableStepImpl` as `suspend`. Wrap existing callers (Main.kt entry point, 12 test files) in `runBlocking { ... }` — the same pattern already established in `BranchReconcilerTest.kt:117/143/173/201/229/257/283-284/313/322`.

Construct `BranchReconciler` in `PipelineOrchestrator` constructor (mirroring `BranchReconcilerTest.kt:115`). Thread via `DurableWalkContext.branchReconciler` (field already declared at `DurableWalkContext.kt:29`, currently dead). The reconciler is called at the start of `walkPipelineSpecDurable` to drive per-branch resume decisions.

The run-level `cursorStore.load(runId)` fallback is accepted for M3-R4.4; branch-level cursor API is deferred to M3-R5.

## Alternatives Considered

1. **`runBlocking` at the boundary**: Wrap the entire `PipelineOrchestrator.run()` body in `runBlocking { ... }` and call the reconciler inside that block. Rejected — defeats ADR-0039 structured concurrency semantics and blocks the calling thread.

2. **Synchronous reconciler wrapper**: Create a non-suspend adapter that blocks on the reconciler using `runBlocking`. Rejected — same structural problem as above; creates nested `runBlocking` anti-pattern.

3. **Branch-level cursor API now**: Extend `ReplayCursorStore` with `getStageIndex(branchOpId)` and update `BranchReconciler` to query per-branch checkpoint. Rejected — out of M3-R4.4 scope; U-3 deferred to M3-R5.

## Consequences

- Idiomatic Kotlin coroutines: `suspend` propagates through the entire durable walk chain.
- `BranchReconciler.reconcileRunningOperations` called idiomatically as `suspend` function.
- All callers of `PipelineOrchestrator.run()` in production (`Main.kt`) and tests (12 files) wrap the call in `runBlocking`.
- Findings `arch-4-branch-reconciler-dead-reference` and `overeng-3-dead-field-branch-reconciler` close when the reconciler is wired.
- Run-level cursor fallback documented; branch-level API deferred to M3-R5.
