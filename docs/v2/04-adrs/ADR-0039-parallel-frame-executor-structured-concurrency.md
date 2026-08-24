# ADR-0039: ParallelFrameExecutor Structured Concurrency

- **Status:** Accepted for M3-R4.3
- **Date:** 2026-08-24
- **Decision owners:** Pipeline Kotlin maintainers
- **M3-R4.3 Implementation:** T-07 (C7)
- **Dependency note:** kotlinx-coroutines-core version 1.11.0 (not 1.9.0 as originally proposed)

## Context

The `ParallelFrameExecutor` in M3-R4.2 was a no-op stub that validated branch inputs but did not execute branches concurrently. All branches were executed sequentially in the calling thread.

M3-R4.3 requires real concurrent execution of parallel branches with proper structured concurrency semantics: branch cancellation must propagate from the parent scope, and the `JoinPolicy` determines when the parallel frame is considered complete.

## Decision

Replace the no-op stub with a real implementation using `kotlinx.coroutines` with `coroutineScope { async(Dispatchers.IO) }.awaitAll()`.

### JoinPolicy Dispatch

```kotlin
suspend fun execute(frame: ParallelFrame, ctx: StepExecutorContext): StepResult {
    return when (frame.joinPolicy) {
        JoinPolicy.ALL_COMPLETE -> coroutineScope {
            frame.branches.map { branch ->
                async(Dispatchers.IO) { runBranch(branch, ctx) }
            }.awaitAll().let { StepResult.success(it) }
        }
        JoinPolicy.FIRST_SUCCESS -> coroutineScope {
            val results = frame.branches.map { branch ->
                async(Dispatchers.IO) { runBranch(branch, ctx) }
            }
            val firstSuccess = results.first { it.await().success }
            results.forEach { it.cancel() }
            StepResult.success(listOf(firstSuccess.await()))
        }
        JoinPolicy.ANY_COMPLETE -> coroutineScope {
            val results = frame.branches.map { branch ->
                async(Dispatchers.IO) { runBranch(branch, ctx) }
            }
            val firstDone = results.first { it.isCompleted }
            results.forEach { if (!it.isDone) it.cancel() }
            StepResult.success(listOf(firstDone.await()))
        }
    }
}
```

### Structured Concurrency Properties

1. **Parent cancellation propagates**: When the parent `PipelineRun` scope is cancelled, all child coroutines (branches) are cancelled via `CoroutineScope`.
2. **Exception transparency**: If a branch fails, `awaitAll()` re-throws the first exception and cancels sibling branches.
3. **No leaks**: `coroutineScope` waits for all children before returning, regardless of how the scope exits.
4. **Dispatchers.IO**: Branch execution uses `Dispatchers.IO` for blocking operations. The process executor (already used by `sh` steps) uses `ProcessBuilder` and is therefore unaffected by dispatcher selection.

### Dependency

The implementation requires `kotlinx.coroutines-core` version **1.11.0** (declared in `gradle/libs.versions.toml`). This is a deviation from the original proposal which cited version 1.9.0.

## Alternatives Considered

1. **Thread pool without structured concurrency**: A fixed thread pool (`Executors.newFixedThreadPool(n)`) with manual thread management. Rejected because it does not provide cancellation propagation or exception handling built into structured concurrency.

2. **Undispatched coroutines**: Launch branches without `Dispatchers.IO`. Rejected because the `sh` step blocks on subprocess execution; using the default dispatcher would starve the coroutine system.

3. **kotlinx.coroutines 1.9.0**: The originally proposed version. Rejected because 1.11.0 is the version currently declared in `libs.versions.toml` and available in the build environment.

## Consequences

- Parallel branches execute concurrently; total wall-clock time for `n` branches with equal work is approximately the time of the slowest branch.
- Cancellation of the parent pipeline run cancels all in-flight branches.
- `ALL_COMPLETE` waits for all branches; first failure cancels siblings.
- `FIRST_SUCCESS` cancels all non-winning branches as soon as one succeeds.
- `ANY_COMPLETE` returns when any branch completes, cancelling the rest.
- kotlinx-coroutines-core 1.11.0 must be declared in `pipeline-step-sdk/runtime/build.gradle.kts`.

## Evidence and Provenance

- M3-R4.3 design decision from `design.md` §C7
- ADR-0034 defines the dispatcher boundary (step-sdk/runtime only)
- `ParallelFrameExecutorConcurrentTest` validates all 3 policies × success/cancel combinations
