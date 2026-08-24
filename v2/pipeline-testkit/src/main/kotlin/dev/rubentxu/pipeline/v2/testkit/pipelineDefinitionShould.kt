package dev.rubentxu.pipeline.v2.testkit

import dev.rubentxu.pipeline.v2.domain.PipelineDefinition

@Suppress("unused")
fun pipelineDefinitionShould(): PipelineDefinition =
    PipelineDefinition(id = "seed", name = "seed", version = "0.0.0")
