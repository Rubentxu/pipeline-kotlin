package dev.rubentxu.pipeline.v2.domain

/**
 * Pure-data description of a pipeline: its identity, metadata, steps, and
 * the edges between them.
 *
 * ## M1 baseline
 *
 * Before LF-0202 the type carried only metadata (id, name, version) — it
 * was the *shape of the entity*, not the runtime contract. The
 * [PipelineCompiler] worked implicitly inside `walkPipelineSpecDurable`
 * and never produced a `PipelineDefinition` of any useful substance.
 *
 * ## M2 widened contract
 *
 * LF-0202 widens the type so that:
 * - [id] is the typed [DefinitionId] (LF-0101 contract), not a raw `String`
 *   — closing the loop between the canonical ID contract and the entity it
 *   identifies.
 * - [steps] carries every [StepDescriptor] the pipeline executes. The list
 *   is the authoritative source of truth: `walkPipelineSpecDurable` must
 *   consult it, not re-derive step metadata from raw strings.
 * - [edges] carries the ordering graph (LF-0202 follow-on to LF-0204).
 * - [stages] is reserved for the LF-0207 `canonical parallel` slice; the
 *   field is present but `PipelineCompiler` MUST emit an empty list until
 *   that slice lands.
 *
 * ## Migration status
 *
 * The two legacy `PipelineDefinition(id = "hello", ...)` fixtures in
 * `:pipeline-testkit` keep compiling because the new fields default to
 * empty lists and the new typed [DefinitionId] accepts the legacy literal
 * via [DefinitionId.invoke]. A dedicated migration slice is LF-0205.
 *
 * @see PipelineCompiler
 * @see StepDescriptor
 * @see Edge
 */
data class PipelineDefinition(
    val id: DefinitionId,
    val name: String,
    val version: String,
    val steps: List<StepDescriptor> = emptyList(),
    val edges: List<Edge> = emptyList(),
    val stages: List<Stage> = emptyList(),
) {
    init {
        require(name.isNotBlank()) { "PipelineDefinition.name must not be blank" }
        require(version.isNotBlank()) { "PipelineDefinition.version must not be blank" }
        // The step list must be unique by id. The M2 compiler enforces this;
        // this assertion is the safety net for hand-built definitions.
        val stepIds = steps.map { it.id }
        require(stepIds.toSet().size == stepIds.size) {
            "PipelineDefinition.steps must have unique ids; duplicates: " +
                stepIds.groupBy { it }.filterValues { it.size > 1 }.keys
        }
    }

    /**
     * Lookup a step by its [StepDescriptor.id]. Returns `null` when the step
     * is not present. O(n) but n is small in practice (≤ a few dozen
     * steps); a hashmap would be premature.
     */
    fun step(stepId: String): StepDescriptor? = steps.firstOrNull { it.id == stepId }
}

/**
 * Reserved for the LF-0207 `canonical parallel` slice. The M2 compiler
 * emits an empty list of stages; the runtime walks [PipelineDefinition.edges]
 * directly.
 */
data class Stage(
    val name: String,
    val steps: List<String>,
) {
    init {
        require(name.isNotBlank()) { "Stage.name must not be blank" }
        require(steps.isNotEmpty()) { "Stage.steps must not be empty" }
        require(steps.toSet().size == steps.size) {
            "Stage.steps must be unique; duplicates: " +
                steps.groupBy { it }.filterValues { it.size > 1 }.keys
        }
    }
}
