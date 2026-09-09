# LB-02 EP-F2.5 — StepSpec Execution Decoupling: Consumer Inventory

Status: INVENTORY COMPLETE — NO BEHAVIOR CHANGED (per user decision 2026-09-09).
Decision authority: user checkpoint of EP-F2 ("checkpoint the coupling, decide
execution policy before touching PipelineRun").

## Architectural finding

`StepSpec` currently carries two responsibilities:

```text
StepSpec
    ├── declarative structural IR   (compile/lower → StepNode → coordinator)
    └── legacy executable command   (direct execution inside PipelineRun whens)
```

Target rule (user): **StepSpec is declarative/build-time IR. It MUST NOT be a
runtime execution language.** All execution flows through the canonical spine:
`StepSpec → DslCompiledPipelineCompiler → StepNode →
CanonicalDurableRunCoordinator → Durable Resolution → CommonExecutionBoundary`.

`RegistryStepSpec` (the new sealed leaf) is NOT invalidated. The problem is the
consumers that interpret `StepSpec` as runtime commands.

## Key reachability facts (grounded)

1. **The public CLI path is already fully on the canonical spine.**
   `Main.kt` (composition root):
   - `compiledPipeline.supportsCanonicalDurableExecution() == true` →
     `runCanonicalPipeline(...)` → `CanonicalDurableRunCoordinator(pipeline, runId)`.
   - `pipelineSpec != null` (non-canonical) → **fail-closed** `System.exit(2)`
     (`NON_CANONICAL_CANONICAL_BRIDGE_ERROR`). There is no third runner.
2. `PipelineOrchestrator` is constructed in Main but **never invoked** (LF-0205
   redirect comment: "handed in as a DurableRunDelegate"; only references are
   the import and the construction). It delegates to `walkPipelineSpecDurable`
   (the file misleadingly named `PipelineRun.kt`).
3. Therefore the entire direct-execution block in `PipelineRun.kt`
   (`walkPipelineSpecDurable` → `executeDurableStep(Impl)` and its helpers) is
   **NOT production-reachable from the public CLI today**. It survives only via
   `PipelineOrchestrator` (unwired) and tests.
4. Admission gate: `supportsCanonicalDurableExecution()` uses
   `canonicalCoreStepIds = CanonicalCoreStepMetadata.pluginIds +
   CoreStepRegistryFactory.registry().keys()` — **registry keys are already
   canonical**, so an `OpaqueStepNode` lowered from `RegistryStepSpec` is
   admissible without touching this function (assuming a block-step body rule
   is not triggered; leaf steps are fine).

## Consumer inventory (C1 structural / C2 production direct-exec / C3 test-only / C4 dead)

| # | Consumer | Location | Class | Prod-reachable? | Responsibilities | Dependencies | Migration target |
|---|----------|----------|-------|-----------------|------------------|--------------|------------------|
| 1 | `stepNode(step, ...)` lowering | `DslCompiledPipelineCompiler.kt:141` | C1 | yes (canonical path) | sealed-when → StepNode; has generic `RegistryStepSpec` branch | StepSpec, domain StepNode | keep (this IS the seam) |
| 2 | `blockPayload`, parallel/branch grouping, retry copy helpers (`PipelineDsl.kt:1219+`) | compiler + DSL | C1 | yes | structural transformation only | StepSpec | keep |
| 3 | `BlockStepFlattener.flattenImpl` | `pipeline-step-sdk/api/.../BlockStepFlattener.kt:99` | C1 | yes | body traversal; RegistryStepSpec = leaf | StepSpec | keep |
| 4 | `executeDurableStepImpl` big when | `PipelineRun.kt:640` | C2 | **no** (see fact 2) | direct side effects per concrete StepSpec | ShExecution, echo/sleep SDK, eventSink | migrate orchestrator OR delete after orchestration decision |
| 5 | parallel branch executor when | `PipelineRun.kt:2520` | C2 | no | same, inside parallel fold | ShExecution | same as 4 |
| 6 | `stepTypeMetadata` when (stepType/effects/replay policy) | `PipelineRun.kt:1917` | C2-adjacent | no | journal metadata derivation from StepSpec | DomainReplayPolicy | should move to StepNode-side metadata (plugin StepKey + descriptor effects) |
| 7 | `stepToParams` when (journal params JSON) | `PipelineRun.kt:2000` | C2-adjacent | no | fingerprint params | kotlinx.serialization | same as 6 |
| 8 | `emitParallelStepEvents` / event emission heuristics (`step !is StepSpec.WithCredentialsBlock`, line 221) | `PipelineRun.kt` | C2-adjacent | no | event ordering | eventSink | same as 4 |
| 9 | `StepExecutors.executeBranch` when | `pipeline-step-sdk/runtime/StepExecutors.kt:399` | C3 | used by legacy orchestrator/tests only | direct echo/sh/sleep/error execution | SDK fns | C3: characterize legacy; do not condition new design |
| 10 | `CredentialProjection` when (spec) | `pipeline-domain/credentials/CredentialProjection.kt:155` | C1 | yes (canonical) | credentials projection | StepSpec.CredentialsBinding | keep (typed structural projection) |
| 11 | `CredentialBindingsPayload` when | `application/durable/credentials/CredentialBindingsPayload.kt:54` | C1 | yes | payload encode | same | keep |
| 12 | `WithCredentialsExecutor.bindings` param | `pipeline-credentials-executor/.../WithCredentialsExecutor.kt:94` | C1 | yes | typed binding consumption | CredentialsBinding | keep |
| 13 | Canonical per-step node dispatchers (DeleteDir/CleanWs/WriteFile) referencing `StepSpec.X(...)` in comments/fixtures | `application/durable/Canonical*NodeDispatcher.kt` | C1 | yes | StepNode (not StepSpec) dispatch | domain | keep |
| 14 | `ShExecution` `step: StepSpec.Shell` parameter | `application/durable/ShExecution.kt:88` | C1/C2 boundary | reachable from BOTH spine and PipelineRun | shell effect execution | — | spine already passes through it; keep as effect authority |
| 15 | Architecture tests pinning compiler branches (FArchL7*, FArchLfc1*) | `pipeline-architecture-tests` | C3 | n/a | invariants | reflection | update alongside migration |
| 16 | UAT tests constructing `StepSpec.X` directly (UatDurable*, UatLocal008...) | `src/test` | C3 | n/a | fixtures | — | keep until each migrates to compiled-pipeline fixtures |

## Counters

```text
direct StepSpec execution cases remaining (production-reachable): 0
direct StepSpec execution cases remaining (code total):           4  (#4 #5, plus metadata pair #6 #7)
legacy-only executors (C3):                                       1  (#9)
```

## Execution gateway verdict

The gateway ALREADY EXISTS and is the highest common point:

```text
Main (composition root)
  → runCanonicalPipeline
    → CanonicalDurableRunCoordinator.run(pipeline: CompiledPipeline, runId)
      → StructuralFamilyResolver.classify → Legacy/Registry boundaries
```

It accepts `CompiledPipeline`/`StepNode` — NOT `StepRegistry` as a mechanism
(the registry reaches the coordinator as an already-wired dependency for the
Registry family only). `PipelineRun.kt` direct execution is a redundant second
runner behind the unwired `PipelineOrchestrator`.

## EP-F2.5 stop condition check

NOT triggered. The public pipeline path does NOT require any
PipelineRun direct-execution when: the CLI is fail-closed onto the canonical
spine, and `RegistryStepSpec`-lowered `OpaqueStepNode`s are already admitted by
`canonicalCoreStepIds` (registry keys included).

## Minimal migration slice (proposal, not yet executed)

1. Wire nothing new. `RegistryStepSpec` leaf → generic lowering (done in F2) →
   coordinator Registry family is the COMPLETE production path for an external
   step from a real `.pipeline.kts`.
2. Resolve the fate of `PipelineOrchestrator` + `PipelineRun.kt`
   (`walkPipelineSpecDurable`): either delete (C4) after its C3 tests migrate,
   or explicitly re-point it at the coordinator. That is a Legacy Burn-down
   item, not a blocker for F2 completion.
3. Then F2 success criterion is demonstrable with zero PipelineRun changes:
   real `.pipeline.kts` with a `registryStep(...)` → compiler lowering →
   `OpaqueStepNode` → coordinator → registry admission → StepHandler.
