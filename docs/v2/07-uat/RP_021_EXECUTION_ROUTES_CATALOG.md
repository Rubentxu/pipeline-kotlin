# RP-021 — Supported Execution Routes Catalog

**Status**: DRAFT (2026-09-27T10:21Z, branch `wu/d-019-rp021-routes-catalog`).
**Authority**: `docs/v2/05-roadmap/ROADMAP.md` §4 (RP-021), AGENTS.md
hexagonal architecture + Step Constitution mandates.
**Scope**: every supported execution path that the engine must accept
without privileged `StepKey` branching. This document is the inventory
that any future Step (core or external) inherits automatically.

## 0. Decision model

The engine matches a closed structural ADT
(`ExecutionNode` / `StepBodies`); it MUST NOT `when` over plugin Step
classes. `StepKey → StepDefinition → StepHandler` resolves via an open
registry.

This document enumerates the **axes of variation** that the engine
itself recognises:

```text
A. Authoring surface
   A1. Declarative        pipeline { stages { stage { steps { <step>(...) } } } }
   A2. Scripted           script { ... <step>(...) ... }

B. Memory mode
   B1. In-memory          no journal on disk, single-run coordinator
   B2. Durable            SQLite journal + operation journal + replay cursor

C. Control flow
   C0. Linear             sequential steps in stage body
   C1. Branch             when / if / switch over a sealed ADT predicate
   C2. Loop               retry / timeout / parallel as composable Named Bodies
   C3. Wait               waitUntil polling a typed predicate with deadline
   C4. Sub-pipeline       nested pipeline via canonical Step invocation

D. Lifecycle
   D1. Fresh              first execution, no durable history
   D2. Restart/resume     resume from journal after interruption
   D3. Replay             re-execute from durable journal for divergence
   D4. Cancel             cooperative cancellation via Deadline + CancellationException
```

Every combination of (A × B × C × D) is supported as long as each axis
combination has at least one golden test that exercises it. The
catalogue below is the authoritative inventory.

## 1. Authoring surface

### A1. Declarative

Entry point: `pipeline { ... }` compiled to `ExecutionNode` via
`DslCompiledPipelineCompiler`. The DSL MUST NOT perform I/O; values are
constructed, never observed.

**Golden test surface**:
- `DslCompiledPipelineCompilerTest` (testClasses)
- `PipelineCompileKtsFixtures` (`v2/compatibility/<NN>-*.pipeline.kts`)

**Implementation files**:
- `v2/pipeline-domain/src/main/kotlin/.../domain/CompiledPipeline.kt`
- `v2/pipeline-application/src/main/kotlin/.../application/CompositionRoot.kt`

### A2. Scripted

Entry point: `script { ... }` compiled to `CompiledScriptedEntryPoint`.
Runtime values flow through typed Step outputs; the script body itself
is an effect interpreter, not a constructor.

**Golden test surface**:
- `ScriptedScopeTest`, `ScriptedRegistryInvokerTest`
- `ScriptedIsUnixRuntimeTest`, `ScriptedPwdRuntimeTest`

**Implementation files**:
- `v2/pipeline-application/src/main/kotlin/.../application/scripted/`

## 2. Memory mode

### B1. In-memory

Coordinator: `InMemoryRunCoordinator` /
`InMemoryCompiledRunCoordinator`. No journal. Single run only. Used by
tests and short-lived invocations.

**Golden test surface**:
- `InMemoryRunCoordinatorTest`
- `InMemoryCompiledRunCoordinatorTest`
- `FArchM2CanonicalRunCoordinatorTest` (architecture fitness)

**Implementation files**:
- `v2/pipeline-domain/src/main/kotlin/.../domain/InMemoryRunCoordinator.kt`
- `v2/pipeline-domain/src/main/kotlin/.../domain/InMemoryCompiledRunCoordinator.kt`

### B2. Durable

Coordinator: `CanonicalDurableRunCoordinator`. SQLite-backed journal
(`SqliteEventStore`), operation journal, retry control journal,
replay cursor store.

**Golden test surface**:
- `CanonicalCoordinatorScopeStackTest`
- `SqliteEventStoreRoundTripTest`,
  `SqliteEventStoreConcurrencyCharacterisationTest` (RP-020)
- `OperationJournalTest`, `DbLockContractTest`,
  `OperationJournalRunIdContractTest`

**Implementation files**:
- `v2/pipeline-application/src/main/kotlin/.../application/durable/CanonicalDurableRunCoordinator.kt`
- `v2/pipeline-events/src/main/kotlin/.../events/store/`

## 3. Control flow

### C0. Linear

Sequential Step execution inside a stage body. Default mode.

**Implementation**: `StepBodies.Sequential` /
`BodyExecutionPolicy.Sequential`.

### C1. Branch

`when` / `if` over a sealed ADT predicate; re-enters the engine via
`BranchInvoker.invokeAll`.

**Golden test surface**:
- `WULpr023BranchInvokerTest`
- `BodyInvokerSeamTest`

**Implementation files**:
- `v2/pipeline-domain/src/main/kotlin/.../domain/durable/BranchInvoker.kt`
- `v2/pipeline-domain/src/main/kotlin/.../domain/step/BodyInterpreter.kt`

### C2. Loop (composable Named Bodies)

`retry`, `timeout`, `parallel` are composable Named Bodies, NOT
permanent stage-terminals. Each Block Step declares its own body-shape
contract and routes through the shared body machinery.

**Golden test surface**:
- `RetryReconcilerTest`, `RetryAwareDispatchIntegrationTest`
- `ParallelReconcilerTest`,
  `ParallelAggregateJournalContractTest`
- `BodyInvokerSeamTest`

**Implementation files**:
- `v2/pipeline-domain/src/main/kotlin/.../domain/durable/RetryReconciler.kt`
- `v2/pipeline-domain/src/main/kotlin/.../domain/durable/ParallelReconciler.kt`

### C3. Wait

`waitUntil` polling a typed predicate with deadline. Re-enters via
`BranchInvoker` once the predicate resolves.

**Golden test surface**:
- `WaitUntilReconcilerTest`

**Implementation files**:
- `v2/pipeline-domain/src/main/kotlin/.../domain/durable/WaitUntilReconciler.kt`

### C4. Sub-pipeline (nested)

A pipeline as a canonical Step invocation. Recursion is bounded by
the configured depth limit.

**Golden test surface**: covered transitively by all corpus fixtures
that include nested pipelines (e.g. `examples/02-multi-stage.pipeline.kts`).

## 4. Lifecycle

### D1. Fresh

No durable history. `ReplayPolicy` does not short-circuit. Handler
runs.

### D2. Restart/resume

Resume from journal after interruption. The control journal persists
the durable control row BEFORE child effects (retry control row
mandatory since ADR-0075).

**Golden test surface**:
- `RetryReconcilerTest`
- `CanonicalCoordinatorScopeStackTest`

### D3. Replay

Re-execute from durable journal for divergence detection. The decision
lives in `EffectReplayPolicy.decide`, never per-Step.

**Golden test surface**:
- `DivergenceDetectorContractTest`, `DivergenceDetectorTest`
- `E-EM-11 Closure Receipt` (ADR-0074, NEVER policy)

### D4. Cancel

Cooperative cancellation via `Deadline` + `CancellationException`.
The engine converts cancellation into typed outcomes folded by the
declared policy.

**Golden test surface**:
- `ExternalBodyDeadlineCancellationProofTest`

**Implementation files**:
- PAR-D law (ADR-0076): `Coroutine cancellation is NEVER durable truth`

## 5. Cross-axis combinations (current coverage)

| A | B | C | D | Golden test path |
|---|---|---|---|---|
| A1 Declarative | B1 InMemory | C0 Linear | D1 Fresh | `PipelineCompileKtsFixtures` |
| A1 Declarative | B2 Durable | C0 Linear | D1 Fresh | corpus fixtures |
| A1 Declarative | B2 Durable | C0 Linear | D2 Restart | `RetryReconcilerTest` |
| A1 Declarative | B2 Durable | C0 Linear | D3 Replay | `DivergenceDetectorTest` |
| A1 Declarative | B2 Durable | C1 Branch | D1 Fresh | `WULpr023BranchInvokerTest` |
| A1 Declarative | B2 Durable | C2 Loop (retry) | D1 Fresh | `RetryReconcilerTest` |
| A1 Declarative | B2 Durable | C2 Loop (parallel) | D1 Fresh | `ParallelReconcilerTest` |
| A1 Declarative | B2 Durable | C3 Wait | D1 Fresh | `WaitUntilReconcilerTest` |
| A1 Declarative | B2 Durable | C0 Linear | D4 Cancel | `ExternalBodyDeadlineCancellationProofTest` |
| A2 Scripted | B1 InMemory | C0 Linear | D1 Fresh | `ScriptedIsUnixRuntimeTest` |
| A2 Scripted | B2 Durable | C0 Linear | D1 Fresh | `ScriptedScopeTest` |

## 6. Privileged-StepKey audit

A scan of the durable coordinator confirms zero privileged routing by
`StepKey` / `stepName`:

```bash
grep -rn 'when(stepKey)\|when(stepName)\|when (stepKey)\|when (stepName)' \
  v2/pipeline-application/src/main \
  v2/pipeline-domain/src/main
```

Output expected: empty. If a result is found, it MUST be classified
as legacy and queued for elimination in RP-3/RP-5 (no privileged core
path is allowed).

## 7. Legacy / exception paths (declared before elimination)

The following paths exist in the codebase but are NOT certified:

| Path | Reason | Target elimination |
|---|---|---|
| `core.scripting.*` legacy dispatch (DSL builder effects) | superseded by ADR-0073 body re-entry | RP-3 WU-RP-031 |
| Direct `StepSpec` execution in `PipelineRun` / `PipelineOrchestrator` | F2.5 finding frozen | RP-3 (burn-down) |

These are kept under the legacy catalogue until G5 LEGACY_REMOVED for
each entry. No new path may join this list without explicit ADR
acceptance.

## 8. Inventory SHA & evidence

- Generated: 2026-09-27T10:21Z
- HEAD at generation: `b247874a1504bc34da0d7a8671514eba4f0adda9`
- WI: `a64ca468-2969-4c87-be05-87e5bd3b5588` (D-019)
- Cycle: `p-733fb505b5a6bd2d/train-1-d019-rp021-routes-catalog`

Regeneration: re-run the test discovery (`find v2 -path '*/test/*'
-name '*.kt' | xargs grep -lE ...`) and the privileged-StepKey grep
above; both must match this document.
