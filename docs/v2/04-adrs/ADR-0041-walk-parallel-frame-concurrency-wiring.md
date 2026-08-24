# ADR-0041: walkParallelFrame Concurrency Wiring

- **Status:** Accepted for M3-R4.4
- **Date:** 2026-08-24
- **Decision owners:** Pipeline Kotlin maintainers
- **M3-R4.4 Implementation:** T-04 (C4)

## Context

`walkParallelFrame` (`PipelineRun.kt:1221-1319`) dispatches branches sequentially via `forEachIndexed { ... walkBranchDurable(...) }` at line 1277. `ParallelFrameExecutor` (`StepExecutors.kt:165-387`) provides concurrent branch execution via `coroutineScope { async(Dispatchers.IO) }.awaitAll()`. However, the executor's private `runBranch` (`StepExecutors.kt:323-386`) bypasses the durable journal — it calls SDK step functions with `NoOpEventSink` and has no access to `journal`, `cursorStore`, `eventSink`, or `divergenceDetector`.

The `walkBranchDurable` function (`PipelineRun.kt:1338-1466`) IS the durable executor — it threads all journal machinery and emits `ParallelBranchStarted`/`Finished` events correctly.

## Decision

Keep `ParallelFrameExecutor` as a concurrency primitive only. Do NOT wire `executor.execute(frame, context)` into `walkParallelFrame`. Instead, replace the sequential `forEachIndexed` at `PipelineRun.kt:1277` with:

```kotlin
coroutineScope {
    frame.branches.map { branch ->
        async(Dispatchers.IO) { walkBranchDurable(...) }
    }.awaitAll()
}
```

This preserves durable semantics: `walkBranchDurable` handles `beginOperation`, `eventSink` appends, `cursorStore` advances, and divergence checking. The `coroutineScope` provides structured concurrency (parent cancellation propagates; exception transparency; no leaks). `Dispatchers.IO` is used for blocking `sh` subprocess execution.

Remove the stale comment at `PipelineRun.kt:1275`: `"// Walk each branch sequentially (T-05 will add concurrency via ParallelFrameExecutor)"`.

## Alternatives Considered

1. **Wire `executor.execute(frame, context)` directly**: Pass journal + cursorStore + eventSink into the executor via new constructor parameters. Rejected — leaks the durable substrate into the step-sdk/runtime layer; changes the executor contract from "concurrency primitive" to "durable executor"; more invasive.

2. **Use executor's `runBranch` directly**: Port the executor's `runBranch` logic (including nested parallel frame recursion) into `walkBranchDurable`. Rejected — HIGH risk (R-2): bypasses the durable replay/gating machinery that `walkBranchDurable` provides; would require duplicating all journal integration logic.

3. **Thread pool without structured concurrency**: Use `Executors.newFixedThreadPool(n)` with manual thread management. Rejected — violates ADR-0039 structured concurrency semantics; no cancellation propagation; no built-in exception handling.

## Consequences

- Branches in a parallel frame execute concurrently; total wall-clock time approximates the slowest branch (not the sum).
- Durable semantics (journal, cursor, divergence) stay in `walkBranchDurable` — one place, not duplicated.
- `arch-7-parallel-frame-executor-not-wired` closes.
- `ParallelFrameExecutor` stays as a concurrency primitive; its `execute()` method remains `suspend` but is not called from the production walk path (test-only usage via `ParallelFrameExecutorConcurrentTest` remains valid).
