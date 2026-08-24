# ADR-0035: advancePastParallelFrame Join Barrier Semantics

- **Status:** Accepted for M3-R4.2
- **Date:** 2026-08-24
- **Decision owners:** Pipeline Kotlin maintainers
- **M3-R4.2 Implementation:** T-06 (C6)

## Context

When a `ParallelFrame` completes (all branches finish or one branch triggers early termination), the replay cursor must advance past the parallel region atomically. The `advancePastParallelFrame` function is the **join barrier** — it computes the next stage index after all parallel branches have reported their results.

The critical concurrency invariant: **no two threads may call `advancePastParallelFrame` simultaneously for the same parallel frame**, and the stage index advancement must be atomic (CAS semantics) to prevent replay cursor regression.

## Decision

### Signature

```kotlin
fun advancePastParallelFrame(
    frame: ParallelFrame,
    branchResults: List<BranchResult>
): StageIndex
```

### Semantics

1. **Stage index computation**: `nextStageIndex = max(branchResults.map { it.stageIndex })`

2. **Atomic CAS write**: Uses the same lock mechanism as `advancePastStage` — a `DbLock` over `"stage-index-$runId"` with SQLite `busy_timeout=5000`. The CAS pattern:
   ```
   current = readStageIndex(runId)
   ok = casStageIndex(runId, current, nextStageIndex)  // single SQL UPDATE WHERE current=expected
   if (!ok) retry  // DbLock ensures serialized retry
   ```

3. **JoinPolicy handling**:
   - `ALL_COMPLETE`: waits for all branches, then advances by `max` stage
   - `FIRST_SUCCESS`: as soon as one branch succeeds, others are cancelled; advance to that branch's stage
   - `ANY_COMPLETE`: advance to the stage of whichever branch finishes first

4. **Return value**: the new `StageIndex` after CAS succeeds

### Concurrency stress note

> **⚠️ Deferred concurrency stress testing**: Full concurrency stress testing for the join barrier (CAS contention under heavy parallel load) is **deferred to M3-R4.3**. The current implementation relies on `DbLock` serialization and SQLite's `busy_timeout`. Production concurrency must be validated with UatDurable010 (planned for R4.3) before promoting to production.

## Alternatives Considered

1. **Optimistic locking without DbLock** — rejected; without serialization, concurrent CAS updates to the same run's stage index could cause one thread to overwrite another's advancement, leading to replay cursor regression.

2. **Global mutex across all parallel frames** — rejected; overly conservative; prevents concurrent parallel frames in different pipeline branches from making progress.

3. **Per-run mutex instead of CAS** — rejected; CAS allows safe concurrent reads and ensures no regression without requiring exclusive locks across the entire pipeline run.

## Consequences

- Parallel frame completion advances the replay cursor atomically
- No replay cursor regression under concurrent branch completion
- `max(branchResults)` ensures replay resumes after the furthest branch
- Deferred concurrency stress testing is documented as a known gap

## Evidence and Provenance

- M3-R4.2 design decision from `design.md` §C6
- ADR-0033 defines `JoinPolicy` enum used here
- ADR-0032 established `DbLock` as the locking primitive
- DEBT-2026-08-24-UAT010-CONCURRENCY-STRESS (deferred to R4.3)
