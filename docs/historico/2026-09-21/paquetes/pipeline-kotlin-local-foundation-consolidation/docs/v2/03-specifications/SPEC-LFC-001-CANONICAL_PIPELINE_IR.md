# SPEC-LFC-001 — Canonical Pipeline IR

**Status:** proposed

## Objective

Define the only executable and inspectable pipeline model. The object compiled from the DSL is the object validated, planned, executed and projected to graph.

## Model

```kotlin
data class CompiledPipeline(
    val id: PipelineDefinitionId,
    val source: SourceDescriptor,
    val agent: AgentSpec?,
    val environment: EnvironmentSpec,
    val options: List<OptionSpec>,
    val parameters: List<ParameterSpec>,
    val tools: List<ToolSpec>,
    val stages: List<StageNode>,
    val post: PostSpec?,
    val pluginLockDigest: Digest,
)

data class StageNode(
    val id: StageId,
    val name: String,
    val agent: AgentSpec?,
    val environment: EnvironmentSpec,
    val options: List<OptionSpec>,
    val whenCondition: ConditionSpec?,
    val input: InputSpec?,
    val body: StageBody,
    val post: PostSpec?,
)

sealed interface StageBody {
    data class Steps(val steps: List<StepNode>) : StageBody
    data class NestedStages(val stages: List<StageNode>) : StageBody
    data class Parallel(val branches: List<StageNode>) : StageBody
    data class Matrix(val matrix: MatrixSpec) : StageBody
}

sealed interface StepNode {
    val id: StepId
    val pluginStepId: PluginStepId
}
```

Plugin-specific payloads must be serialized with a schema/version identifier; core execution must not require a giant sealed class containing every ecosystem plugin.

## IDs

IDs are stable within a compiled definition and do not depend on runtime list indexes. Runtime identities add `RunId`, `AttemptId` and `OperationId` rather than passing `(runId, stageIndex, stepIndex)` bundles.

## Required properties

- serializable/versioned;
- source locations retained for diagnostics;
- deterministic for identical source + plugin lock + compiler/DSL version;
- capable of round-trip inspection without loading plugin implementation code when descriptors/schemas suffice;
- nested bodies explicitly represented.

## Acceptance

No production execution path consumes a second pipeline-definition type.
