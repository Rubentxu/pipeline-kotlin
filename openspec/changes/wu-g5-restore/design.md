# Design: wu-g5-restore — wire the public `waitUntil { body }` DSL to a canonical RepeatUntil dispatch

**Cycle:** wu-g5-restore
**Branch:** cycle/wu-g5-restore @ 387bf8de
**Base:** ca28959f (post lfc2-fixture-debt merge; trunk `0f4c110b`)
**Date:** 2026-09-16
**Path:** A-lite (refined — see §1 Context Reuse Check)
**Status:** partial — see §1.1 launch-plan discrepancy and §10 Open Questions

---

## 1. Context Reuse Check

The launch plan asserts that the G5a canonical path components
(`BlockShellScope.RepeatUntil`, `dispatchRepeatUntilBody`, `WaitUntilReconciler`,
`FileBasedWaitUntilControlJournal`) are **already present** in HEAD and that
"RESTORE does NOT modify those bodies". This is **factually wrong** for the
current branch HEAD `387bf8de`.

### 1.1 Discrepancy between launch plan and actual code state

| Claim in launch plan | Evidence at HEAD `387bf8de` |
|---|---|
| `BodyExecutionPolicy.RepeatUntil` is a closed ADT case (5th) | 4 cases only — `Sequential / Scoped / Retrying / Parallel` (file ends at L114 with no `RepeatUntil`); KDoc still says "4 cases" |
| `BlockShellScope.RepeatUntil(initialRecurrencePeriod, deadlineMs)` exists | 6 cases only — `None / Directory / TimestampsScope / EnvScope / Timeout / Retry` |
| `dispatchBody` routes through a `RepeatUntil` branch | `dispatchBody` (`CanonicalDurableRunCoordinator.kt:1505-1578`) has no `RepeatUntil` arm; the `when (scope)` ends at `is BlockShellScope.EnvScope ->` |
| `dispatchRepeatUntilBody` exists as the canonical durable loop | Function does not exist (`grep -rn dispatchRepeatUntilBody v2` returns 0 hits) |
| `WaitUntilReconciler` exists as a pure reconciler mirror of `RetryReconciler` | File does not exist in `v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/durable/` |
| `FileBasedWaitUntilControlJournal` exists as a single-writer durable store | File does not exist in `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/` |

The G5a code did exist at commits `92971881` (G0–G5 durable RepeatUntil) and
`b56859f6` (G6–G8 contract suite + real example). It was **physically reverted
in commit `06f9e32e`** (`waitUntil G5 implementation reversal: BodyInvoker
re-entry is a prerequisite`) and the production source was restored to the
pre-G5a baseline. The G5 reversal commit cites three orthogonal blockers:

1. `BodyInvoker.invoke(body, context)` is not a public dispatch method on the
   canonical engine — `BODY_INVOKER_CAPABILITY` exists for handler-declared
   re-entry but `dispatchBody` has no `RepeatUntil` branch.
2. `core.waitUntil` removal from `LEGACY_PLUGIN_IDS` requires a working
   replacement dispatch path (sequencing error: legacy was removed before the
   new path was operational; `fixture 13` regressed).
3. Replacement dispatch path verified before `LEGACY_REMOVED` (G0..G8 burn-down
   discipline mirrored for Block Steps).

The `dispatchBody` switch over `BlockShellScope` at HEAD ends with
`is BlockShellScope.EnvScope ->` (line 1564) — the gap between `Retry` and the
`invokeBodyChildren` call site at line 1591 is the exact place where a
`BlockShellScope.RepeatUntil` projection must land.

### 1.2 Implication for the launch plan

The proposal/explore/spec statements that "the canonical path is RETAINED from
G5a" are not true at HEAD. The slice scope MUST be reframed honestly:

**Original launch plan:** DSL body capture + 1 ADT case + 1 compiler case +
verify reachability through existing canonical machinery.
**Actual scope at HEAD:** DSL body capture + 1 ADT case + 1 compiler case +
**re-introduce the canonical RepeatUntil machinery** (reconciler, journal,
dispatch branch, BlockShellScope case, projection arm) — bounded to the
minimum needed for `dispatchRepeatUntilBody` to compile and dispatch.

The forward gates from `06f9e32e` are not bypassed by this slice: the canonical
path **must re-enter the engine through `BodyInvoker.invoke`** to satisfy
ADR-0073. That surface already exists at HEAD
(`CanonicalBodyInvokerAdapter.invoke(body, context)` at L98, plus
`bodyInvokerAdapter.open(bodyRef) { invokeBodyChildren(...) }` at L1590 of
`CanonicalDurableRunCoordinator.kt`) and is the route `Retry`'s
`dispatchRetryAwareBody` already uses — the new `dispatchRepeatUntilBody` MUST
follow the same pattern.

---

## 2. Technical Approach

The slice is split into two ordered halves that share the same DSL/compiler
wiring but differ in **how much canonical-path code re-enters**:

**Half A — DSL/algebra (WU-G5R.1 + WU-G5R.2).** Re-shape the public DSL into a
body-bearing algebraic variant. This half does NOT depend on canonical
machinery existing; it only restructures `StepSpec`, the DSL function, and the
compiler projection switch.

**Half B — canonical path (WU-G5R.3 + WU-G5R.4).** Either cherry-pick the G5a
canonical path from `92971881` (broad) or surgically add only the minimum
needed for `core.waitUntil` (narrow). Both options include:
1. `BodyExecutionPolicy.RepeatUntil(val policy: RepeatUntilPolicy)` 5th case
2. `BodyExecutionPolicyShape.REPEAT_UNTIL` enum member
3. `BodyExecutionSupport.SCOPED_SEQUENTIAL_RETRYING_REPEAT_UNTIL` constant
4. `BlockShellScope.RepeatUntil(initialRecurrencePeriod, deadlineMs)` 7th case
5. `BlockShellScope.repeatUntil(...)` projection arm (B11 W3b pattern; mirror
   `decodeAttemptBudget` / `BlockShellScope.Retry`)
6. `dispatchBody` `when (scope)` arm `is BlockShellScope.RepeatUntil -> ...`
7. `dispatchRepeatUntilBody` function (mirrors `dispatchRetryAwareBody`)
8. `WaitUntilReconciler` (pure; mirrors `RetryReconciler`)
9. `FileBasedWaitUntilControlJournal` (single-writer; mirrors
   `FileBasedRetryControlJournal`)
10. `WaitUntilIdentityFactory` + `WaitUntilControlState` +
    `WaitUntilControlRowSnapshot` + `WaitUntilReconciliationInput/Decision`

The orchestrator picks the option in §4 / §10. Either way, the **resulting code
shape at HEAD must match `92971881` exactly** — the slice does not invent new
machinery, it re-introduces certified G5a machinery under a fresh slice
header.

---

## 3. Architecture Decisions

### 3.1 Decision: rename terminal `StepSpec.WaitUntil` to body-bearing `StepSpec.WaitUntilBlock`

**Choice:** Rename `StepSpec.WaitUntil(initialRecurrencePeriod, quiet)` →
`StepSpec.WaitUntilBlock(initialRecurrencePeriod, body: List<StepSpec>, quiet)`.
No alias, no `ReplaceWith` deprecation.

**Alternatives:** (a) Add `body` as nullable on the existing data class
(rejected — flagged bag, makes invalid `WaitUntil(body = null)` representable).
(b) Keep terminal `WaitUntil` and add `WaitUntilBlock` as a separate variant
(rejected — only one path is reachable from the public DSL; the terminal form
is never used; renaming prevents accidental reintroduction).

**Rationale:** The terminal form has no live call sites outside the eager
DSL fun body (`PipelineDsl.kt:1686`) and the `else -> OpaqueStepNode` catch-all
(`DslCompiledPipelineCompiler.kt:228`). Renaming in one slice is cheaper than
carrying two variants forward. Closed ADT-first (AGENTS.md §8).

### 3.2 Decision: DSL `waitUntil { body }` captures body via scope-threaded `StepsScope` mechanism

**Choice:** The public DSL fun mirrors `retry`/`timeout` exactly:
```kotlin
fun StageScope.waitUntil(initialRecurrencePeriod: Long = 1L, quiet: Boolean = false, body: StageScope.() -> Unit) {
    val inner = StageScope(stageName, runtimeConfig)
    inner.body()
    steps.add(StepSpec.WaitUntilBlock(initialRecurrencePeriod = initialRecurrencePeriod, body = inner.steps.toList(), quiet = quiet))
}
```

**Alternatives:** (a) `StepsScope` instead of `StageScope` (rejected — every
existing block-owning DSL fun in the file uses `StageScope`; consistency wins).
(b) Capture the body as `() -> List<StepSpec>` and resolve at runtime
(rejected — lambda in DSL builder is forbidden by AGENTS.md §10; producer must
construct declarative data).

**Rationale:** Eager evaluation is a forbidden DSL pattern (AGENTS.md §10).
Reusing the established `StageScope` mechanism guarantees consistent threading
of `stageName` / `runtimeConfig` and matches the retry/timeout/dir/timestamps
pattern byte-for-byte. The body is data, not behaviour.

### 3.3 Decision: `BodyExecutionPolicy.RepeatUntil(val policy: RepeatUntilPolicy)` is a closed ADT case (5th)

**Choice:** Add `RepeatUntil` after `Parallel`. `RepeatUntilPolicy` carries
`initialRecurrencePeriod: Duration` + `quiet: Boolean` + `deadline: Duration?`
placeholder. The `BodyExecutionPolicyShape` enum gains `REPEAT_UNTIL`. The
default support constant
`BodyExecutionSupport.SCOPED_SEQUENTIAL_RETRYING_REPEAT_UNTIL` replaces
`SCOPED_SEQUENTIAL_RETRYING` in production composition (the coordinator wires
the new constant at `L476` of `CanonicalDurableRunCoordinator.kt`).

**Alternatives:** (a) `Boolean repeat: Boolean` flag on a new
`BodyExecutionPolicy.Repeating(policy)` case (rejected — flagged bag, see
AGENTS.md §8). (b) Add a parallel `WaitUntilPolicy` family and keep `RepeatUntil`
out of the closed ADT (rejected — fragmentation defeats the B10 W1d invariant
that policy resolution is a closed-family operation).

**Rationale:** Closed ADT preserves B10 W1b representability invariant
(criterion 7) and AGENTS.md §8 "ADT-first modelling" — every body-execution
shape the engine implements is a value of the closed ADT. The reconciler is
pure; the coordinator interprets (AGENTS.md §7). The `quiet` field stays on
the policy because it controls observability (`WaitUntilPolled` emission),
not loop control — it is engine-visible policy, not block metadata.

### 3.4 Decision: `BlockShellScope.RepeatUntil` is a 7th case (mirrors `BlockShellScope.Retry`)

**Choice:** Add `data class RepeatUntil(val initialRecurrencePeriod: Duration, val deadline: Duration? = null) : BlockShellScope` after `Retry`. The
`when (scope)` switch in `dispatchBody` (currently 6 arms, L1505–L1578) gains
a 7th arm mirroring `BlockShellScope.Retry`'s `childShOptions` construction
plus an `eventSink.append(WaitUntilScheduled(...))` emission.

**Alternatives:** (a) Reuse `BlockShellScope.Retry` (rejected — semantics are
opposite: retry is failure-bounded, repeat-until is success-bounded; reusing
would conflate two distinct typed events). (b) Add a
`BodyExecutionProjection.Until` arm only and project to
`BlockShellScope.None` (rejected — loses the deadline/recurrence contract; the
reconciler has nothing to interpret).

**Rationale:** `BlockShellScope` is the engine's typed carrier for body-execution
shapes; adding a case is the closed-ADT idiom (B10 W1c). Mirroring `Retry`'s
shape (recurrence + deadline + retry control journal) is the minimal re-entry
surface the reconciler needs.

### 3.5 Decision: `dispatchRepeatUntilBody` re-enters via `BodyInvoker.invoke` (ADR-0073)

**Choice:** New function modelled on `dispatchRetryAwareBody` (L1789). Sequence:
1. `journal.readState` → `WaitUntilControlState`
2. `WaitUntilReconciler.reconcile(state, today)` →
   `WaitUntilReconciliationDecision`
3. `journal.beginAttempt` (persist-before-effects; mirror RETRY-D)
4. `eventSink.append(WaitUntilPolled(...))`
5. `bodyInvokerAdapter.open(bodyRef) { invokeBodyChildren(...) }` — re-entry
6. Fold body outcome → `OperationStatus`
7. `journal.updateStatus`
8. On `SUCCEEDED` → `eventSink.append(WaitUntilCompleted(...))` → return
9. Thread-local sentinel set inside the function (read by E2E fitness)

**Alternatives:** (a) Build a `dispatch*Block` collection alongside
`dispatchRetryAwareBody` (rejected — AGENTS.md §STEP CONSTITUTION explicit
forbidden; the launch plan's open question #1 mandates the body-machinery
route). (b) Inline the loop in `dispatchBody` (rejected — violates the
`dispatch*AwareBody` separation; future readers cannot find the canonical loop).

**Rationale:** ADR-0073 is the operative law — block Steps re-enter the engine
through `BodyInvoker.invoke`. The retry precedent (`dispatchRetryAwareBody`)
is the proof shape; the same body-machinery seam works for success-bounded
loops. The thread-local sentinel is the structural proof the E2E fitness reads
(launch plan §2 "Canonical dispatch reachability").

### 3.6 Decision: `WaitUntilReconciler` is pure (mirrors `RetryReconciler`)

**Choice:** `fun reconcile(input: WaitUntilReconciliationInput): WaitUntilReconciliationDecision`. No I/O, no clock, no journal mutation. Reads
`state.controlRows` + `input.fingerprint` + `input.now` + `input.policy`;
returns one of `ScheduleAttempt(n) / ResumeAttempt(n) / AdvanceAfterSuccess(n -> n+1)
 / DeadlineExceeded / Aborted`. Same atomic-write semantics as RETRY-D
(ADR-0075 §11).

**Alternatives:** (a) Stateful reconciler that mutates the journal
(rejected — violates AGENTS.md §7 "decide purely, then interpret"; risks
re-introducing the G5a bug that produced 26/11 reds). (b) Merge
`WaitUntilReconciler` into `RetryReconciler` via a generic
`LoopReconciler<E>` (rejected — repeats the polymorphic-dispatcher defect
AGENTS.md §13 forbids).

**Rationale:** Pure reconciler is testable without constructing the coordinator
(11 `WaitUntilReconcilerTest` cases proved this at `92971881`; the file is
part of the cherry-pick or surgical-add). ADR-0075 + PAR-D + AGENTS.md §7.

### 3.7 Decision: `FileBasedWaitUntilControlJournal` is a single-writer durable store

**Choice:** File at `{controlRoot}/wait-until-control/{runId}/{stepId}.json`.
Atomic rename, fingerprint gate,
`WaitUntilControlJournalDivergenceException`. Mirrors
`FileBasedRetryControlJournal` byte-for-byte.

**Alternatives:** (a) Reuse `FileBasedRetryControlJournal` (rejected — type
system must distinguish the two control row kinds; a
`WaitUntilControlRowSnapshot` cannot be persisted as a `RetryControlRowSnapshot`
without an unchecked cast). (b) Skip the journal entirely and rely on
in-memory counter (rejected — R2 closed-loop lesson: in-memory counter + a
second invocation of the binary re-runs the entire loop and duplicates a
previously successful child effect).

**Rationale:** ADR-0075 §11 single-writer journal; AGENTS.md §RETRY-D
("durable control row, not an event"). The journal is created fresh at first
run; no schema migration is required (out of scope per spec.md §Out of Scope).

### 3.8 Decision: LEGACY removal is OUT of scope (WU-G5B)

**Choice:** RESTORE keeps `CanonicalWaitUntilNodeDispatcher` physically present
(legacy stub). `LEGACY_PLUGIN_IDS` still contains `"core.waitUntil"`.
`CanonicalCoreStepDecoder` row stays. `CanonicalCoreStepMetadata` row stays.
`Lfc2WaitUntilNoLegacyRoutingFitnessTest` is **intentionally RED** during
RESTORE; flip happens in WU-G5B.

**Alternatives:** (a) Remove the legacy dispatcher as part of RESTORE
(rejected — sequencing error that produced the original G5 regression;
`06f9e32e` cites this as Blocker #2). (b) Remove `core.waitUntil` from
`CoreStepRegistryFactory` and keep the legacy dispatcher (rejected — violates
spec.md cross-cutting constraint: `CoreWaitUntilStep.registerInto(this)` MUST
be removed; AGENTS.md §STEP CONSTITUTION forbids `core.waitUntil` in the
registry).

**Rationale:** Burn-down discipline (G0..G8) requires physical removal to come
AFTER canonical reachability is proven; this slice proves reachability only.
`Lfc2WaitUntilNoLegacyRoutingFitnessTest` is the forward gate that flips
GREEN only after WU-G5B.

### 3.9 Decision: HALF-B cherry-pick vs surgical-add — orchestrator decision required

**Choice:** Orchestrator picks between:
- **P1 (cherry-pick):** `git checkout 92971881 -- v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/step/BodyExecutionPolicy.kt v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/StepDescriptorRegistry.kt v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/durable/WaitUntilReconciler.kt v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/durable/WaitUntilReconciliationDecision.kt v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/durable/WaitUntilReconciliationInput.kt v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/durable/WaitUntilControlRowSnapshot.kt v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/FileBasedWaitUntilControlJournal.kt v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/WaitUntilControlState.kt v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/WaitUntilIdentityFactory.kt` plus the `BlockShellScope.RepeatUntil` arm + `dispatchRepeatUntilBody` in `CanonicalDurableRunCoordinator.kt`. Then re-introduce `WaitUntilReconcilerTest` from the same SHA. **Pros:** exact G5a behaviour, no invention. **Cons:** large diff (~1500 LOC), re-triggers pre-existing reds from W1a..W1d (must re-baseline), risk of re-introducing fixture-13 regression if `BlockInvoker.invoke` re-entry surface has drifted since G5a.
- **P2 (surgical-add):** Cherry-pick ONLY `WaitUntilReconciler` + `WaitUntilReconciliation{Input,Decision}` + `WaitUntilControlRowSnapshot` + `FileBasedWaitUntilControlJournal` + `WaitUntilIdentityFactory` + `WaitUntilControlState` + the `BlockShellScope.RepeatUntil` case + the `dispatchRepeatUntilBody` function + the `projectBodyExecution` arm. Hand-port (not cherry-pick) the ADT case to the current 4-case `BodyExecutionPolicy.kt` so the diff is minimal. **Pros:** small surgical diff (~400 LOC); no re-baselining required; matches the launch plan's "5 cases" target. **Cons:** slight drift from G5a's exact code; risk of subtle behaviour differences in the reconciler.

**Rationale:** Both options are technically viable. P1 is faster to write but
slower to merge (re-baseline cost); P2 is slower to write but faster to merge.
The orchestrator owns the decision (see §10 open question #1).

---

## 4. Data Flow

### 4.1 AST → IR → dispatch (canonical path, post-WU-G5R.4)

```text
                  public DSL: pipeline { stages { stage("s") {
                    steps { waitUntil(initialRecurrencePeriod = 100L) {
                      sh("test -f /tmp/marker")
                    } } } } }
                                              |
                                              v
   StageScope.waitUntil(...)                 (PipelineDsl.kt, body capture)
        inner.block()                        -- no eager evaluation
        steps.add(StepSpec.WaitUntilBlock(
            initialRecurrencePeriod=100L,
            body=[StepSpec.Sh(...)],
            quiet=false))
                                              |
                                              v
   DslCompiledPipelineCompiler.stepNode      (compiler projection)
        is StepSpec.WaitUntilBlock -> blockStepNode(...)
        blockStepNode -> BlockStepNode(
            id           = ".../waitUntil-body-0",
            pluginStepId = PluginStepId("core.waitUntil"),
            payload      = { kind:"waitUntilBlock",
                              initialRecurrencePeriod:100 },
            body         = [OpaqueStepNode("core.sh", ...)])
                                              |
                                              v
   CanonicalDurableRunCoordinator.dispatchStep
        -> dispatchBody(block, ...)
            bodyPolicyResolver.resolve("core.waitUntil")
                -> StepDescriptorRegistry.standard().definition("core.waitUntil")
                -> descriptor.body.declared.execution.policy
                = BodyExecutionPolicy.RepeatUntil(
                    RepeatUntilPolicy(initialRecurrencePeriod=100.millis,
                                      deadline=null,
                                      quiet=false))
            support.supports(...) == true (SCOPED_SEQUENTIAL_RETRYING_REPEAT_UNTIL)
            projectBodyExecution(policy, shOptions)
                -> BodyExecutionProjection.Scope(BlockShellScope.RepeatUntil(
                    initialRecurrencePeriod=100.millis, deadline=null))
            scope: BlockShellScope.RepeatUntil ->
                childShOptions (mirrors Retry's construction)
                bodyInvokerAdapter.open(bodyRef) {
                    invokeBodyChildren(...)    <-- ADR-0073 re-entry
                } while loop driven by:
                    FileBasedWaitUntilControlJournal.readState
                    WaitUntilReconciler.reconcile  (pure)
                    journal.beginAttempt (persist-before-effects)
                    eventSink.append(WaitUntilPolled)
                    fold child outcome -> OperationStatus
                    journal.updateStatus
                    eventSink.append(WaitUntilCompleted on SUCCEEDED)
                                              |
                                              v
   Event harness reads: WaitUntilPolled (N attempts),
                        WaitUntilCompleted(reason="completed")
                        -- origin=canonical
```

### 4.2 Failure flow (deadline exceeded)

```text
   deadline exceeded at attempt K
        -> WaitUntilReconciler.reconcile returns DeadlineExceeded(attempts=K)
        -> eventSink.append(WaitUntilCompleted(reason="deadline"))
        -> body outcome folds to StepOutcome.Failure(PipelineFailure(ENGINE, "..."))
        -> run exits non-zero
```

---

## 5. File Changes

| File | Action | Description | Slice |
|---|---|---|---|
| `v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/PipelineDsl.kt` | Modify | Rename `StepSpec.WaitUntil` (L645-651) → `StepSpec.WaitUntilBlock(initialRecurrencePeriod, body: List<StepSpec>, quiet)`; rewrite `waitUntil` (L1677-1693) to capture body via `StageScope`; remove eager `condition()` invocation; remove `throw RuntimeException(...)` | WU-G5R.2 |
| `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/DslCompiledPipelineCompiler.kt` | Modify | Add `is StepSpec.WaitUntilBlock -> blockStepNode(...)` arm in `stepNode` (before `else`); add `is StepSpec.WaitUntilBlock -> step.body` arm in `blockStepNode`'s inner `when`; add `is StepSpec.WaitUntilBlock -> Json.encodeToString(...)` arm in `blockPayload` (kind="waitUntilBlock") | WU-G5R.3 |
| `v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/step/BodyExecutionPolicy.kt` | Modify | Add `data class RepeatUntil(val policy: RepeatUntilPolicy) : BodyExecutionPolicy` 5th case; add `BodyExecutionPolicyShape.REPEAT_UNTIL` enum member; add `data class RepeatUntilPolicy(initialRecurrencePeriod: Duration, deadline: Duration? = null, quiet: Boolean = false)`; add `BodyExecutionSupport.SCOPED_SEQUENTIAL_RETRYING_REPEAT_UNTIL` constant; add `RepeatUntil -> REPEAT_UNTIL` arm in `shape` getter; update KDoc "4 cases" → "5 cases" | WU-G5R.3 (P2) or P1 |
| `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt` | Modify | Add `data class RepeatUntil(val initialRecurrencePeriod: Duration, val deadline: Duration? = null) : BlockShellScope` (7th case, after `Retry`); add `is BodyExecutionPolicy.RepeatUntil -> decodeRepeatUntilBudget()` arm in `projectBodyExecution`; add `is BlockShellScope.RepeatUntil -> ...` arm in `dispatchBody`'s inner `when (scope)`; add new private `decodeRepeatUntilBudget()` projector (default 1000ms, deadline default `Long.MAX_VALUE`); add new private `suspend fun dispatchRepeatUntilBody(...)` modelled on `dispatchRetryAwareBody` | WU-G5R.3 |
| `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreStepRegistryFactory.kt` | Modify | Remove `CoreWaitUntilStep.registerInto(this)` call (L117) — `core.waitUntil` is structural (ADR-0073), NOT a registry Step; AGENTS.md §STEP CONSTITUTION | WU-G5R.3 |
| `v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/StepDescriptorRegistry.kt` | Modify | (P1 only) Add `core.waitUntil` descriptor row with `body = StepBody.Declared(invocation=ZERO_OR_MORE, BodyExecution(owner=CANONICAL_ENGINE, RepeatUntil(...)))`; or (P2) keep current registry without `core.waitUntil` and rely on `pluginStepId`-keyed dispatch | WU-G5R.3 (P1) or design choice (P2) |
| `v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/durable/WaitUntilReconciler.kt` | Create (P1) or hand-port (P2) | Pure reconciler `(WaitUntilReconciliationInput) -> WaitUntilReconciliationDecision` | WU-G5R.3 |
| `v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/durable/WaitUntilReconciliationInput.kt` | Create or hand-port | Pure input data class | WU-G5R.3 |
| `v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/durable/WaitUntilReconciliationDecision.kt` | Create or hand-port | Closed ADT: `ScheduleAttempt / ResumeAttempt / AdvanceAfterSuccess / DeadlineExceeded / Aborted` | WU-G5R.3 |
| `v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/durable/WaitUntilControlRowSnapshot.kt` | Create or hand-port | Durable control row snapshot (mirror of retry's) | WU-G5R.3 |
| `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/FileBasedWaitUntilControlJournal.kt` | Create or hand-port | Single-writer durable journal under `{controlRoot}/wait-until-control/{runId}/{stepId}.json` | WU-G5R.3 |
| `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/WaitUntilIdentityFactory.kt` | Create or hand-port | `controlOpId + childOpId` derivation with `WAIT_UNTIL_ATTEMPT_MARKER = PluginStepId("wait-until-attempt")` | WU-G5R.3 |
| `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/WaitUntilControlState.kt` | Create or hand-port | Pure read snapshot | WU-G5R.3 |
| `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt` | Modify | Add `waitUntilControlJournal: FileBasedWaitUntilControlJournal? = null` ctor parameter (mirrors `retryControlJournal`); wire into `dispatchBody` at the new `is BlockShellScope.RepeatUntil` arm | WU-G5R.3 |
| `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/Main.kt` | Modify | At L136, remove `core.waitUntil` from the printed residual-LEGACY set note (or keep — it remains in LEGACY_PLUGIN_IDS during RESTORE per spec.md §Out of Scope) | WU-G5R.3 (cosmetic; defer to WU-G5B) |
| `v2/compatibility/22-wait-until.pipeline.kts` | Create | Real example: `sh("touch /tmp/marker")` + `waitUntil(period=100L) { sh("test -f /tmp/marker && echo READY") }` + `sh("rm /tmp/marker")` | WU-G5R.4 |
| `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/Lfc2WaitUntilDslCanonicalProjectionTest.kt` | Create | RED characterization test (fails today for expected reason; passes after WU-G5R.3) | WU-G5R.1 |
| `v2/pipeline-architecture-tests/src/test/kotlin/dev/rubentxu/pipeline/v2/architecture/Lfc2WaitUntilDslDoesNotLowerToOpaqueStepFitnessTest.kt` | Create | Asserts every `StepSpec.WaitUntilBlock` lowers to a `BlockStepNode` (not `OpaqueStepNode`) | WU-G5R.3 |
| `v2/pipeline-architecture-tests/src/test/kotlin/dev/rubentxu/pipeline/v2/architecture/Lfc2WaitUntilCanonicalReentryFitnessTest.kt` | Create | Asserts `dispatchBody` reaches `dispatchRepeatUntilBody` (thread-local sentinel) and `WaitUntilReconciler.reconcile` is invoked at least once | WU-G5R.4 |
| `v2/pipeline-architecture-tests/src/test/kotlin/dev/rubentxu/pipeline/v2/architecture/Lfc2WaitUntilNoLegacyRoutingFitnessTest.kt` | Create | Source-level scan asserting no production source references `core.waitUntil` through legacy dispatch vocabulary outside `CanonicalWaitUntilNodeDispatcher.kt` and documented imports. **Intentionally RED during RESTORE.** | WU-G5R.4 |
| `docs/v2/07-uat/WU_G5_RESTORE_CLOSURE_RECEIPT.md` | Create | Closure receipt: slice evidence, pre/post L4/L5 gate counts, inventory row update | end-of-apply |

### 5.1 File change counts

```text
Modified:  8 production files (DSL x1, compiler x1, BodyExecutionPolicy x1,
           CanonicalDurableRunCoordinator x1, CoreStepRegistryFactory x1,
           StepDescriptorRegistry x1, Main x1 [cosmetic], BodyExecutionPolicyTest x1)
Created:   ~12 files (6 domain + 6 application canonical-path files + 4 test files + 1 example + 1 receipt)
Deleted:   0 production files (legacy dispatcher stays for WU-G5B)
Net delta: +20 files, ~1700 LOC P1 OR +12 files, ~400 LOC P2
```

---

## 6. Interfaces / Contracts

### 6.1 `StepSpec.WaitUntilBlock` (new, replaces terminal `StepSpec.WaitUntil`)

```kotlin
data class WaitUntilBlock(
    val initialRecurrencePeriod: Long = 1L,
    val body: List<StepSpec> = emptyList(),
    val quiet: Boolean = false,
) : StepSpec {
    override val name: String get() = "waitUntil"
    override val type: String get() = "waitUntil"
}
```

### 6.2 `BodyExecutionPolicy.RepeatUntil` + `RepeatUntilPolicy` + `BodyExecutionPolicyShape.REPEAT_UNTIL`

```kotlin
sealed interface BodyExecutionPolicy {
    data object Sequential : BodyExecutionPolicy
    data class Scoped(val projection: BodyContextProjection) : BodyExecutionPolicy
    data class Retrying(val policy: RetryPolicy) : BodyExecutionPolicy
    data class Parallel(val policy: ParallelPolicy) : BodyExecutionPolicy
    data class RepeatUntil(val policy: RepeatUntilPolicy) : BodyExecutionPolicy   // 5th case

    val shape: BodyExecutionPolicyShape get() = when (this) {
        is Sequential -> SEQUENTIAL
        is Scoped -> SCOPED
        is Retrying -> RETRYING
        is Parallel -> PARALLEL
        is RepeatUntil -> REPEAT_UNTIL
    }
}

enum class BodyExecutionPolicyShape { SEQUENTIAL, SCOPED, RETRYING, PARALLEL, REPEAT_UNTIL }

data class RepeatUntilPolicy(
    val initialRecurrencePeriod: kotlin.time.Duration,
    val deadline: kotlin.time.Duration? = null,
    val quiet: Boolean = false,
)
```

### 6.3 `BodyExecutionSupport.SCOPED_SEQUENTIAL_RETRYING_REPEAT_UNTIL` (new constant)

```kotlin
val SCOPED_SEQUENTIAL_RETRYING_REPEAT_UNTIL: BodyExecutionSupport =
    BodyExecutionSupport(setOf(
        BodyExecutionPolicyShape.SEQUENTIAL,
        BodyExecutionPolicyShape.SCOPED,
        BodyExecutionPolicyShape.RETRYING,
        BodyExecutionPolicyShape.REPEAT_UNTIL,
    ))
```

The current production default at `CanonicalDurableRunCoordinator.kt:476` swaps
from `SCOPED_SEQUENTIAL_RETRYING` to `SCOPED_SEQUENTIAL_RETRYING_REPEAT_UNTIL`.

### 6.4 `BlockShellScope.RepeatUntil` (new 7th case)

```kotlin
private sealed interface BlockShellScope {
    data object None : BlockShellScope
    data class Directory(val target: Path, val previous: Path) : BlockShellScope
    data class TimestampsScope(val runId: String) : BlockShellScope
    data class EnvScope(val overrides: List<String>, val parentEnv: Map<String, SecretHandle>) : BlockShellScope
    data class Timeout(val budgetMs: Long) : BlockShellScope
    data class Retry(val maxAttempts: Int) : BlockShellScope
    data class RepeatUntil(                                                // 7th case
        val initialRecurrencePeriodMs: Long,
        val deadlineMs: Long? = null,
    ) : BlockShellScope
}
```

### 6.5 `dispatchRepeatUntilBody` (new, mirrors `dispatchRetryAwareBody`)

```kotlin
// Thread-local sentinel read by Lfc2WaitUntilCanonicalReentryFitnessTest.
private val canonicalReentrySentinel: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

private suspend fun dispatchRepeatUntilBody(
    block: BlockStepNode,
    runId: RunId,
    stageName: String,
    stageIndex: Int,
    stepIndex: Int,
    stageShOptions: ShOptions,
    parentBodyPath: List<BlockSegment>,
    executionContext: ExecutionContext,
): StepOutcome {
    canonicalReentrySentinel.set(true)
    checkNotNull(waitUntilControlJournal) { "dispatchRepeatUntilBody requires waitUntilControlJournal" }
    // ... (RETRY-D-equivalent: journal.readState → reconciler.reconcile → journal.beginAttempt
    //      → WaitUntilPolled event → bodyInvokerAdapter.open(bodyRef) { invokeBodyChildren(...) }
    //      → fold outcome → journal.updateStatus → WaitUntilCompleted on SUCCEEDED → return)
}
```

### 6.6 `WaitUntilReconciler.reconcile` (pure, mirrors `RetryReconciler.reconcile`)

```kotlin
fun interface WaitUntilReconciler {
    fun reconcile(input: WaitUntilReconciliationInput): WaitUntilReconciliationDecision
}

sealed interface WaitUntilReconciliationDecision {
    data class ScheduleAttempt(val attempt: Int) : WaitUntilReconciliationDecision
    data class ResumeAttempt(val attempt: Int) : WaitUntilReconciliationDecision
    data class AdvanceAfterSuccess(val from: Int, val to: Int) : WaitUntilReconciliationDecision
    data object DeadlineExceeded : WaitUntilReconciliationDecision
    data object Aborted : WaitUntilReconciliationDecision
}
```

---

## 7. Architecture Model

- **Impact:** boundary — DSL → compiler → coordinator → durable journal. The
  new `WaitUntilBlock` StepSpec algebraic variant crosses the closed structural
  IR seam; the new `BodyExecutionPolicy.RepeatUntil` case closes the B10 W1b
  ADT (5 cases); the new `BlockShellScope.RepeatUntil` case closes the body
  execution scope family (7 cases); `dispatchRepeatUntilBody` re-enters via
  `BodyInvoker.invoke` (ADR-0073).
- **Observed baseline:** 4-case `BodyExecutionPolicy`; 6-case `BlockShellScope`;
  no `dispatchRepeatUntilBody`; legacy `CanonicalWaitUntilNodeDispatcher`
  routes `core.waitUntil`; `core.waitUntil` is in `LEGACY_PLUGIN_IDS` and
  `CanonicalCoreStepDecoder`; `CoreWaitUntilStep` is a registry candidate
  (`CoreStepRegistryFactory.kt:117`).
- **Planned intent:** 5-case `BodyExecutionPolicy`; 7-case `BlockShellScope`;
  `dispatchRepeatUntilBody` reaches `WaitUntilReconciler` + durable journal;
  DSL `waitUntil { body }` lowers to `BlockStepNode(BodyExecutionPolicy.RepeatUntil)`;
  `dispatchRepeatUntilBody` re-enters through `BodyInvoker.invoke` (ADR-0073);
  legacy dispatcher still present (intentional, WU-G5B-owned).
- **Render:** n/a — slice does not add new C4 nodes; existing C4 model
  (`sddk-c4-likec4`) is unaffected.

---

## 8. Testing Strategy

| Layer | What to Test | Approach |
|---|---|---|
| L0 compile | `:pipeline-domain:compileTestKotlin` + `:pipeline-application:compileTestKotlin` + `:pipeline-scripting-api:compileKotlin` after each micro-slice | `timeout 600 ./gradlew -p v2 :pipeline-application:compileTestKotlin` |
| L1 direct | `Lfc2WaitUntilDslCanonicalProjectionTest` (RED characterization); `PipelineDslSealedHierarchyTest` (variant added); `BodyExecutionPolicyTest` (5th case row); `WaitUntilReconcilerTest` (re-introduced 11 cases from `92971881`) | `timeout 600 ./gradlew -p v2 :pipeline-application:test --tests 'Lfc2WaitUntilDslCanonicalProjectionTest'` |
| L2 module | `:pipeline-application:test --tests 'Lfc2WaitUntil*'` (all 4 new fitness); `:pipeline-domain:test --tests 'WaitUntilReconcilerTest'`; `:pipeline-domain:test --tests 'BodyExecutionPolicyTest'` | `timeout 600 ./gradlew -p v2 :pipeline-application:test --tests 'Lfc2WaitUntil*'` |
| L3 related | `:pipeline-architecture-tests:test --tests 'Lfc2BlockStepCompilerBodyExhaustivenessFitnessTest'` (auto-discovers new variant); `CompatibilityCorpusTest` (corpus 20 → 21 with fixture 22); `UatLocal011WorkflowControlTest` (still GREEN with the new wiring) | targeted patterns only; never bare `:pipeline-application:test` |
| L4 apply/verify | `:pipeline-application:test` + `:pipeline-architecture-tests:test` + `:pipeline-events:test` (incremental, derived budget); pre-existing reds (`CanonicalDurableRunCoordinatorTest 26/11`, `CompatibilityCorpusTest 20/2`, `Lfc0GlobalStateFitnessTest 1`, UAT 005/007/008/009, fixture14) MUST NOT widen | derived budget = last green × 1.3 floor 600 ceiling 1800 |
| L5 gate | `./gradlew -p v2 check` (incremental) once at end of apply/verify round | escalated only if G0 baseline rebaseline is required (P1 option) |
| E2E (HF5) | `v2/compatibility/22-wait-until.pipeline.kts` runs in fresh / `--rerun` / `--resume` modes via installed CLI; event harness records `WaitUntilPolled` (≥1) + `WaitUntilCompleted(reason="completed")` from `origin=canonical` | `./gradlew :pipeline-application:installDist && pipeline run --db <tmp> --control-root <tmp> --rerun --file v2/compatibility/22-wait-until.pipeline.kts` |

### 8.1 Test law: distinguish failure classes (E-EM-11 NEVER-1)

Each new test asserts:
```text
failureKind == the Step's contractual kind  (not INFRASTRUCTURE)
message     == the Step's configured message  (not "Replay aborted")
```
The E2E fitness reads `origin=canonical` (sentinel on `WaitUntilPolled`/
`WaitUntilCompleted` events) to distinguish the canonical emitter from the
legacy stub.

### 8.2 Inventory update at WU-G5-RESTORE close

`docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md` row for `core.waitUntil` flips to:
`Path = structural`, `StepDefinition = N/A (structural, not a Step)`,
`Canonical = Y (BlockStepNode + BodyExecutionPolicy.RepeatUntil + dispatchRepeatUntilBody)`,
`Legacy = Y (until WU-G5B)`,
`State = IMPLEMENTED_UNCERTIFIED` with `canonical_path_user_reachable = true`
(flipped from `false`) and `previous_g5_evidence = INVALIDATED`.

---

## 9. Migration / Rollout

No data migration. No schema change. The
`FileBasedWaitUntilControlJournal` is created fresh at first run; existing
runs do not have a wait-until-control row (they reach waitUntil via the
legacy stub path). The receipt `B14_WAITUNTIL_G5A_INVALIDATION_RECEIPT.md`
is referenced, not modified. The legacy `CanonicalWaitUntilNodeDispatcher`
remains physically present until WU-G5B; its removal is the burn-down
ledger flip G8 in the WU-G5B cycle.

**Rollback:** `git revert <wu-g5-restore-merge-commit>` returns to the
pre-slice state. No durable state changes; no journal rows pre-created;
inventory row reverts to its pre-slice text.

---

## 10. Open Questions

1. **P1 (cherry-pick from `92971881`) vs P2 (surgical-add)** — orchestrator
   decides. P1 is exact G5a behaviour with large diff + re-baseline cost;
   P2 is small diff with hand-port risk. The pre-existing red baseline
   (`CanonicalDurableRunCoordinatorTest 26/11`, `CompatibilityCorpusTest 20/2`)
   constrains which option is viable: P1 widens the diff surface, P2 keeps
   it tight. **Recommendation: P2** (surgical-add, ~400 LOC) — matches the
   launch plan's "bounded A-lite" framing and avoids re-baselining pre-existing
   reds.
2. **Should `CoreWaitUntilStep` remain registered in `CoreStepRegistryFactory`?**
   The launch plan / spec.md says it MUST be removed (AGENTS.md §STEP
   CONSTITUTION: structural constructs are NOT registry Steps). But removing
   the registration also removes the LFC-2E1-S2-A8 stub which the legacy
   decoder falls through to when `core.waitUntil` is reached without a body.
   Decision: REMOVE the registration (per spec.md cross-cutting constraint).
   The legacy `CanonicalWaitUntilNodeDispatcher.dispatchStub` remains the
   fallback for any non-DSL callers.
3. **Predicate event name** — the spec.md proposes `WaitUntilPredicateEvaluated`
   as a typed event emitted by the body. The reconciler folds this. Design
   needs confirmation that the body step (`sh "test -f marker"`) can emit
   such a typed event; today `core.sh` returns typed `ShResult`, not a
   predicate event. Either (a) the reconciler infers success from a
   non-zero exit code → fold as `predicate=true`; (b) the body emits via a
   new `WaitUntilPredicateEvaluated(result: Boolean)` event channel that
   the canonical engine routes back into the reconciler. **Recommendation (a)**
   for RESTORE; (b) deferred.
4. **`StepDescriptorRegistry` registration of `core.waitUntil`** — P1 keeps
   the G5a row (`body = StepBody.Declared(invocation=ZERO_OR_MORE,
   BodyExecution(owner=CANONICAL_ENGINE, RepeatUntil(...)))`); P2 omits it and
   relies on `pluginStepId`-keyed dispatch. P2 is consistent with the
   "structural, not registry" framing but means `bodyPolicyResolver.resolve`
   must special-case `core.waitUntil` (reject by Step name = forbidden). This
   tension needs a clean answer — possibly a new resolver that accepts
   `BlockStepNode`-level policy overrides for structural keys.
5. **`waitUntilControlJournal` ctor wiring** — the new ctor parameter
   mirrors `retryControlJournal` (L462 of `CanonicalDurableRunCoordinator`).
   The `Main.kt` composition root (L405 / L704) must wire it. Verify
   whether any other call sites exist (find all `CanonicalDurableRunCoordinator(`
   constructors in the codebase) and whether each one has access to
   `controlDirRoot` for the new journal.

---

## 11. ADR Candidates

- **Decision: structural orchestration over registry Step for `core.waitUntil`**
  — hard to reverse + surprising (it conflicts with the registry-first
  pattern of LFC-2E1) + real trade-off (body-machinery re-entry vs
  StepHandler composition). Worth `ADR-0083` (or fold into ADR-0073
  amendment).
- **Decision: `RepeatUntil` is success-bounded; `Retry` is failure-bounded;
  `Parallel` is concurrency-bounded** — three distinct closed ADT cases,
  not three flags on one. Hard to reverse (changing the ADT ripples through
  every `when`); surprising (one might expect a single "Repeating" shape);
  real trade-off (one ADT case per legitimate shape, no flag bag).
  Worth `ADR-0084`.
- **Decision: LEGACY removal is WU-G5B, not RESTORE** — hard to reverse (it
  reorders the burn-down ledger) + surprising (it leaves `core.waitUntil`
  in `LEGACY_PLUGIN_IDS` for one extra cycle) + real trade-off (sequencing
  correctness vs forward motion). Already cited in `06f9e32e`; worth an ADR
  capturing the sequencing law.
- **Decision: thread-local sentinel as structural reachability proof** —
  surprising (it is not pure) but bounded (read only by the E2E fitness,
  not production code) + real trade-off (sentinel in production code is a
  smell, but it is the cheapest possible structural proof). Worth a
  paragraph in the closure receipt rather than a dedicated ADR.

---

## 12. Standard Envelope

```yaml
status: partial
executive_summary: |
  WU-G5-RESTORE design grounded against the actual source at HEAD 387bf8de. The
  launch plan's premise that the canonical RepeatUntil path components are
  RETAINED from G5a is contradicted by the source: those components were
  physically reverted in commit 06f9e32e (waitUntil G5 implementation reversal)
  due to three orthogonal blockers (BodyInvoker re-entry surface, sequencing
  with LEGACY_REMOVED, burn-down discipline). The slice scope is reframed:
  RESTORE = DSL/algebra/compiler rewire AND re-introduction of the canonical
  RepeatUntil machinery (reconciler, journal, dispatch branch, BlockShellScope
  case, projection arm). Two sub-paths proposed (cherry-pick P1, surgical-add
  P2); orchestrator decides. LEGACY_REMOVED remains WU-G5B.
artifacts:
  - "openspec/changes/wu-g5-restore/design"
summary:
  approach: |
    DSL waitUntil {body} lowers to BlockStepNode with body-bearing
    StepSpec.WaitUntilBlock. BodyExecutionPolicy gains RepeatUntil (5th case).
    BlockShellScope gains RepeatUntil (7th case). dispatchRepeatUntilBody
    re-enters through BodyInvoker.invoke, mirrors dispatchRetryAwareBody
    (RETRY-D precedent). WaitUntilReconciler + FileBasedWaitUntilControlJournal
    are re-introduced (cherry-pick or hand-port). CoreWaitUntilStep is REMOVED
    from CoreStepRegistryFactory (structural, not registry).
  key_decisions: 9
  files_affected:
    new: 12
    modified: 8
    deleted: 0
  testing_strategy: |
    L0 compile per micro-slice; L1 Lfc2WaitUntilDslCanonicalProjectionTest (RED
    characterization); L2 module suites (Lfc2WaitUntil* fitness +
    WaitUntilReconcilerTest + BodyExecutionPolicyTest); L3
    Lfc2BlockStepCompilerBodyExhaustivenessFitnessTest auto-discovery;
    L4 module suites + pre-existing-red baseline unchanged; L5 ./gradlew -p v2
    check (incremental) once at end of round; E2E installed-CLI
    v2/compatibility/22-wait-until.pipeline.kts in fresh/--rerun/--resume.
  adr_candidates: 4
architecture:
  impact: boundary
  manifest_ref: n/a (existing C4 model unaffected; structural IR seam widens)
  semantic_status: insufficient_evidence
  render_status: not_applicable
open_questions:
  - P1 (cherry-pick from 92971881) vs P2 (surgical-add) — orchestrator decision
  - CoreWaitUntilStep removal consequence on legacy fallback
  - Predicate event naming (WaitUntilPredicateEvaluated vs exit-code fold)
  - StepDescriptorRegistry treatment of structural core.waitUntil
  - waitUntilControlJournal ctor wiring at all CanonicalDurableRunCoordinator sites
next_recommended: sddk-tasks (after orchestrator resolves open questions)
risks:
  - BodyInvoker re-entry surface drift since G5a (06f9e32e Blocker #1)
  - Re-introduction of fixture-13 regression if dispatchRepeatUntilBody is
    not byte-equivalent to 92971881 implementation
  - Pre-existing reds (CanonicalDurableRunCoordinatorTest 26/11, CompatibilityCorpusTest
    20/2, Lfc0GlobalStateFitnessTest 1, UAT 005/007/008/009, fixture14) widening
    during re-baseline
  - Eager-evaluation defect regression slipping back into DSL fun
  - LEGACY_PLUGIN_IDS re-introduction as accidental side effect of P1
    cherry-pick touching decoder / metadata files
```

---

## 13. References

- `openspec/changes/wu-g5-restore/explore.md` (committed 7845b3e7)
- `openspec/changes/wu-g5-restore/proposal.md` (committed f8778128)
- `openspec/changes/wu-g5-restore/spec.md` (committed 387bf8de)
- `docs/v2/07-uat/B14_WAITUNTIL_G5A_INVALIDATION_RECEIPT.md` (a31b2fa4 — receipt of record)
- `docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md` (ca28959f — inventory row to update)
- ADR-0073 (BodyInvoker re-entry)
- ADR-0075 (RETRY-D durable control rows — analog for wait-until-control)
- ADR-0076 (PAR-D — coroutine execution mechanism, never durable authority)
- ADR-0081 D1/D9 (B11 / W2 / W1d — body re-entry seam; reconcile from durable
  facts, not memory)
- B10 W1b / W1c / W1d receipts (`docs/v2/07-uat/B10_W1{B,C,D}_*.md`)
- B11 W3b receipt (`docs/v2/07-uat/B11_W123_CONTEXT_BLOCKS_RECEIPT.md`)
- G5a commits `92971881` (G0–G5) and `b56859f6` (G6–G8)
- G5 reversal commit `06f9e32e` ("waitUntil G5 implementation reversal")
- LFC-2E0 closure receipt (`docs/v2/07-uat/LFC2E0_CLOSURE_RECEIPT.md`)
- LFC-2E1-S2-A8 G0..G3 receipts (`docs/v2/07-uat/S2_A8_CORE_WAITUNTIL_G*.md`)
- AGENTS.md §STEP CONSTITUTION, §10, §7, §8, §13
- AGENTS.md §RETRY-D (durable control row law, mirrored for wait-until)
- AGENTS.md §PAR-D (coroutine ≠ durable authority)
- AGENTS.md §EXPLICIT IMMUTABLE EXECUTION CONTEXT (CTX-P)
