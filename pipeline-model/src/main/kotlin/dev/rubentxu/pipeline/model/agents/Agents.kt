package dev.rubentxu.pipeline.model.agents

import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.PipelineDomain

interface Agent : PipelineDomain {
    val id: IDComponent
    val name: String
    val labels: List<String>

}

interface Template : PipelineDomain