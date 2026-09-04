package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.Edge
import dev.rubentxu.pipeline.v2.domain.PipelineDefinition
import dev.rubentxu.pipeline.v2.domain.StepDescriptor

/**
 * Maps a DSL-built [PipelineSpec] to the canonical metadata
 * [PipelineDefinition] used at the [dev.rubentxu.pipeline.v2.domain.RunCoordinator]
 * port boundary (LF-0205 redirect).
 *
 * ## Synthetic step ids
 *
 * The DSL steps have no ids; the mapper assigns `s<stageIndex>-<stepIndex>`
 * — mirroring the control-directory naming the durable walker already uses
 * (`$runId-$stageIndex-$stepIndex`). The mapping is deterministic, so the
 * same spec always produces the same definition (characterisation property).
 *
 * ## Edges
 *
 * A single linear chain across the flattened step sequence: the durable
 * walker executes stages in order and steps within a stage in order, so
 * `s0-0 → s0-1 → … → s1-0 → …` reproduces the legacy semantic order
 * exactly (M2 exit criterion: "orden semántico equivalente").
 *
 * ## Placeholders
 *
 * `name` and `version` are placeholders (`"scripted-pipeline"` /
 * `"0.0.0"`) until the compiler contract carries real metadata for
 * scripted pipelines; they satisfy the definition invariants and carry no
 * execution semantics.
 */
object SpecDefinitionMapper {

    fun toDefinition(spec: PipelineSpec, id: DefinitionId): PipelineDefinition {
        val stepIds = mutableListOf<String>()
        spec.stages.forEachIndexed { stageIndex, stage ->
            stage.steps.forEachIndexed { stepIndex, _ ->
                stepIds += "s$stageIndex-$stepIndex"
            }
        }
        val steps = stepIds.map { stepId ->
            StepDescriptor(stepId = stepId, name = "scripted", configRef = stepId)
        }
        val edges = stepIds.zipWithNext().map { (from, to) -> Edge(from = from, to = to) }
        return PipelineDefinition(
            id = id,
            name = "scripted-pipeline",
            version = "0.0.0",
            steps = steps,
            edges = edges,
        )
    }
}
