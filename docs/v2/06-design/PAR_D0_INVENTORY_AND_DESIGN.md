# PAR-D0 — Inventory, semantic matrix and design proposal (NO production changes)

Status: DESIGN / INVENTORY. Base: origin/main `cbd0db1a`. Cycle: PAR-D.
Nothing here is implemented; this document is the gate before any RED test.

---

## 1. INVENTORY — what parallel actually is today

### 1.1 Execution entry points

| Path | Location | Status |
|---|---|---|
| Canonical parallel stage | `CanonicalDurableRunCoordinator.runParallelStage` (L987) via `run()` L445 | PRODUCTION authority |
| Branch dispatch | `executeBranchSteps` (L1061) → shared `dispatch(..., bodyPath=[b{N}:branch, i,step])` | same single spine as linear |
| Legacy `walkParallelFrame` / `PipelineRun.kt` | NOT FOUND in main source | DELETED (references survive only in ADRs + `BranchReconciler` docs + one test) |
| `BranchReconciler` (ADR-0038) | `application/BranchReconciler.kt` | ORPHAN: not wired into any production path; referenced only by its own tests |
| Nested parallel | branches MUST be linear (`EngineInvariantViolation` otherwise) | NOT SUPPORTED, fail-closed |

### 1.2 Branch identity (deterministic)

- `bodyPath = [BlockSegment("b{branchIndex}:branch"), BlockSegment(stepIndex, stepId)]`
- Branch index = `mapIndexed` position in `StageBody.Parallel.branches` (declaration order).
- Journal keys: `OpId(runId, stageIndex, stepIndex, bodyPath)` → `-b{N}` suffixed, length-prefixed.
- **Verdict: identity is already purely structural** (declaration index + stage index + run id). No coroutine/thread/clock inputs. P-identity law already holds; PAR-D must only preserve it.

### 1.3 Durable rows

- Per CHILD STEP: full `OperationJournal` row via the same `dispatch` reconcile path as linear steps (`beginOperation → RUNNING → SUCCEEDED/FAILED`, fingerprint, exactly-once). Child-level replay reuse ALREADY WORKS (WL-P3 evidence).
- Per BRANCH aggregate: **NONE**. Branch outcome exists only in memory inside the `async` block.
- Per STAGE aggregate: **NONE**. No parallel control row, no cursor advance for the parallel stage (`cursorStore.advance` is called only inside linear `dispatch`), no fingerprint.

### 1.4 Concurrency (current, NOT structured)

```kotlin
val scope = CoroutineScope(Dispatchers.Default)   // L1013 — unstructured, never cancelled
branches.mapIndexed { i, b -> scope.async { ... } }
val outcomes = deferred.map { it.await() }        // ALL_COMPLETE join: waits every branch
```

- Exceptions inside `async` propagate at `await()` → caught by `run()`'s generic `catch (Exception)` → INFRASTRUCTURE run failure. Cancellation is never used; process kill is the only mid-flight termination.
- Context stack (`contextStack` var) is mutated by shared `dispatch` from multiple coroutines — today safe only because branches are linear steps that don't push overlays (dir/withEnv inside parallel branches would race: **latent defect, out of PAR-D0 scope, must be inventoried as risk**).

### 1.5 Failure aggregation

`ALL_COMPLETE`: `first failure by lowest branch index`, else `first unstable`, else success. Joins wait for all started branches (no undeclared fail-fast). Branch failure is contained (becomes branch outcome, never a throw).

### 1.6 Events (E-EM-11 contract, keep)

`StageStarted` (once, at `runParallelStage` entry) < `ParallelBranchStarted(i)` … `ParallelBranchFinished(i, outcome)` per branch < caller's `StageFinished`.

**Reuse gap:** `ParallelBranchStarted/Finished` are emitted UNCONDITIONALLY inside the `async` block. On a durable rerun where every child is reused, no new branch execution transition occurs, yet the events are re-emitted. This is exactly the "reuse does not fabricate" tension PAR-D must close (today it fabricates projection events without execution transitions).

### 1.7 Resume/replay behavior today (grounded, WL-P3)

- Whole-pipeline rerun re-enters every stage; parallel stage re-launches all branches as coroutines; each branch re-dispatches children; terminal children are REUSED at step level (0 re-executions); non-terminal children re-execute.
- Net today: "replay" = re-run the coordination, reuse child effects. There is NO aggregate memoization, NO branch-level skip, NO reconciliation decision — the plan is re-executed and children happen to be idempotent-cheap.

### 1.8 What P6 means today

P6 = the parallel STAGE has no durable identity, no aggregate row, and no typed replay decision. Child reuse is incidental (per-step exactly-once), not a parallel policy. P6 asks: make the stage/branch coordination itself a durable, reconciled decision point.

## 2. CRASH-WINDOW MATRIX (design; to be RED-tested before implementation)

Durable facts read from: child journal rows keyed `b{N}` + (proposed) parallel aggregate row. "Aggregate" = stage-level parallel control record.

| W | Durable facts at re-entry | ReconciliationState | Decision | Children executed | Aggregate result | Events projected |
|---|---|---|---|---|---|---|
| W0 | no aggregate row, no branch children | Fresh | Start | all | computed fresh | StageStarted + Branch events as they execute |
| W1 | aggregate row RUNNING (admitted), 0 child rows | AdmittedNotLaunched | Start (re-admit branches) | all | computed fresh | StageStarted + Branch events |
| W2 | some branch children rows exist, none terminal-complete branch | PartiallyRunning | ResumeBranches | only missing/non-terminal children of incomplete branches | computed from all terminals | BranchStarted only for branches with NEW execution; finished branches: no branch events |
| W3 | branch A all children terminal SUCCEEDED; aggregate stale | PartiallyCompleted | ResumeBranches (B only) | A: 0; B: as needed | fold | B branch events only; A: NO fabricated branch events |
| W4 | branch B has terminal FAILED child; aggregate stale | PartiallyCompleted(Failed) | ResumeBranches / CloseFromChildren per policy | terminal children 0 | failure aggregate deterministic (lowest index) | no events for terminal branches |
| W5 | ALL branches' children terminal; aggregate row stale (RUNNING/absent) | AllChildrenTerminal | CloseFromChildren (pure fold, 0 executions anywhere) | 0 | reconstructed | StageFinished only; NO branch events |
| W6 | aggregate row terminal (SUCCEEDED/FAILED) | Succeeded/Failed | ReuseSuccess / ReuseFailure | 0 | reused from aggregate row | no branch events; stage-level projection only |
| W7 | aggregate fingerprint ≠ current structure (branch count/names changed) | Diverged | RejectDivergence | 0 | typed INFRASTRUCTURE failure, fail-closed before any effect | no child events |

**Invariants encoded by the matrix:**
1. A terminal child is NEVER re-executed by a stale aggregate (W3–W6).
2. Reuse paths project NO `ParallelBranchStarted/Finished` (no fabricated transitions).
3. Divergence rejects before any effect (W7), mirroring retry `RejectDivergence`.

### W5 instability caveat (must resolve before implementation)

Branch outcome tri-state is Success/Unstable/Failure in memory, but child journal status has no UNSTABLE. Reconstructing an aggregate from children alone can lose the unstable distinction in the corner where a branch's last child SUCCEEDED but the branch continued (or a catchError overlay folded to unstable). **Proposed resolution:** the aggregate row stores the folded branch outcomes (`branchOutcomes: [i → success|unstable|failure]`) at close time; W5 reconstruction folds from child rows and, if any ambiguity exists, falls back to ResumeBranches-free CloseFromChildren with the conservative fold (failure > unstable > success by lowest index) and records provenance. To be settled in design review, NOT silently.

## 3. PROPOSED ADTs (closed, separated observation vs decision)

```kotlin
/** OBSERVED durable state — pure function of journal rows + aggregate row + structure. */
sealed interface ParallelReconciliationState {
    data object Fresh : ParallelReconciliationState                      // W0
    data object AdmittedNotLaunched : ParallelReconciliationState        // W1
    data class PartiallyCompleted(                                       // W2–W4
        val completedBranches: Map<Int, BranchTerminal>,
        val incompleteBranches: List<Int>,
    ) : ParallelReconciliationState
    data class AllChildrenTerminal(                                      // W5
        val branchOutcomes: Map<Int, BranchTerminal>,
    ) : ParallelReconciliationState
    data class AggregateTerminal(                                        // W6
        val outcome: BranchTerminal,
    ) : ParallelReconciliationState
    data class Diverged(val reason: String) : ParallelReconciliationState // W7
}

sealed interface BranchTerminal { val asText: String
    data object Succeeded : BranchTerminal
    data object Unstable : BranchTerminal
    data class Failed(val failure: PipelineFailure) : BranchTerminal
}

/** DECISION — what the coordinator must DO; no effect happens inside the ADT. */
sealed interface ParallelDecision {
    data class Start(val branches: List<Int>) : ParallelDecision                     // W0/W1: launch all
    data class ResumeBranches(val branches: List<Int>) : ParallelDecision            // W2–W4: launch only these
    data class ReuseSuccess(val outcome: BranchTerminal) : ParallelDecision          // W6
    data class CloseFromChildren(val outcome: BranchTerminal) : ParallelDecision     // W5: fold + persist, 0 executions
    data class RejectDivergence(val reason: String) : ParallelDecision               // W7
}
```

`Start` and `ResumeBranches` kept distinct so the coordinator can prove the "branches NOT executed" set is exactly the complement — auditable against the matrix.

## 4. ParallelFailurePolicy disposition

```kotlin
sealed interface ParallelFailurePolicy {
    data object AwaitAll : ParallelFailurePolicy   // wait all started branches; aggregate = first failure by lowest index
    data object FailFast : ParallelFailurePolicy   // DESIGNED, NOT EXPOSED in PAR-D
}
```

- Current behavior is EXACTLY `AwaitAll`; it becomes the declared default, byte-equivalent.
- `FailFast`: ADT variant exists for design completeness; NOT wired, NOT selectable from the DSL, no UAT requires it. Exposing it is a future explicit decision (would introduce sibling cancellation → needs its own crash windows W2f/W4f).
- No default semantics change in PAR-D.

## 5. Structured concurrency mapping

- `AwaitAll` (implemented): replace the orphan `CoroutineScope(Dispatchers.Default)` with `supervisorScope { async {...}; await all }`. A branch failure is a VALUE (contained outcome), never a coroutine failure — so supervisor semantics (one branch's failure never cancels siblings) match the contract exactly. The scope becomes caller-bound (no leaked scope, structured lifetime).
- `FailFast` (future): would map to `coroutineScope` (child failure → sibling cancellation). Explicitly deferred.
- **Cancellation law (to be enforced by tests + AGENTS candidate at closure):**
  - `CancellationException` is an execution mechanism, NEVER mapped to a generic INFRASTRUCTURE StepFailed.
  - A cancelled coroutine's durable state passes through the same boundary projection as any outcome: the child journal row is reconciled (RUNNING → policy at next resume), the branch is simply not terminal, no fabricated `ParallelBranchFinished`.
  - coroutine cancellation ≠ durable terminal truth.

## 6. Aggregate identity & storage proposal (minimal surface)

- Aggregate row = one `OperationJournal` row at a deterministic aggregate `OpId` (new deterministic bodyPath segment for the stage's parallel control, e.g. `0:parallel-control` under the stage's stepIndex space), fingerprint over `(branchCount, branchNames, joinPolicy)`; statuses map to AggregateTerminal/Fresh/RUNNING.
- **NO new journal schema, NO new store.** W5 reconstruction reads existing child rows; the aggregate is reconstructable from durable child facts + one ordinary journal row. This satisfies the "prefer reconstruct-from-child-facts" directive; if during RED we find a child-level fact is unrecoverable (see §2 caveat), we STOP and raise an ADR instead of extending the schema ad hoc.
- Single-writer law as RETRY-D: a pure `ParallelReconciler` plans; the coordinator (in `runParallelStage`) is the only writer; the driver is plan-only.

## 7. RED test plan (P6 matrix + hierarchy)

Level 0/1 — pure reconciler (`ParallelReconcilerTest`, HF0):
- P6-1 fresh s/s → Start(all)
- P6-2 fresh s/f structure → Start(all); AwaitAll aggregate folds f first (lowest index)
- P6-3 all terminal + aggregate SUCCEEDED → ReuseSuccess, executions 0
- P6-4 all terminal + aggregate FAILED → ReuseFailure, executions 0
- P6-5 A terminal, B incomplete → ResumeBranches([B]); A executions added = 0
- P6-6 all children terminal, aggregate stale → CloseFromChildren, children added = 0
- P6-7 fingerprint divergence → RejectDivergence, children 0
- Matrix-to-window coverage: every W0..W7 row has ≥1 pure test asserting state AND decision AND not-executed set.

Level 2 — focused coordinator (HF1, journal decorator seams, no sleeps):
- Reuse projects no `ParallelBranchStarted/Finished` (P6-3/4/6 at coordinator level).
- Aggregate row written BEFORE branch launch (single-writer, RETRY-D pattern).
- CancellationException never surfaces as generic INFRASTRUCTURE StepFailed.

Level 3 — durable restart/resume (HF3): kill mid-branch → resume → terminal children 0 re-executions, W2–W5 realized end-to-end (extends WL-P3/UatDurable009 lineage).

Level 4 — installDist UAT: UatDsl003 extended with P6 rows; UatParallelBlockDurableTest WL-P1..P3 stay green (AwaitAll byte-equivalence proof).

Baseline debt: the 12 coordinator composition failures stay OPEN, untouched; any row that changes is classified expressly (rule 16 discipline).

## 8. OUT OF SCOPE (unchanged)

Flow output, context parameters, typed Deadline, coordinator decomposition, step burn-down, agent allocation, nested parallel support, FailFast exposure.
