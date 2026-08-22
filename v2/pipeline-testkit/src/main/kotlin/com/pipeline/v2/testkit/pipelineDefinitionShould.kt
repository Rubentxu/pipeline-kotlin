package com.pipeline.v2.testkit

import com.pipeline.v2.domain.PipelineDefinition

@Suppress("unused")
fun pipelineDefinitionShould(): PipelineDefinition =
    PipelineDefinition(id = "seed", name = "seed", version = "0.0.0")
