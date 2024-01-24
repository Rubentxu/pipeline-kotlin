package dev.rubentxu.pipeline.model.agents

import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.PipelineDomain

interface Agent : PipelineDomain {
    val id: IDComponent
    val name: String
    val labels: List<String>
    val type: String

}

class EmptyAgent(
    override val id: IDComponent,
    override val name: String,
    override val labels: List<String>,
    override val type: String
): Agent

interface Template : PipelineDomain