package dev.rubentxu.pipeline.backend.agent


import dev.rubentxu.pipeline.core.cdi.annotations.PipelineComponent
import dev.rubentxu.pipeline.core.interfaces.ILogger
import dev.rubentxu.pipeline.model.agents.IAgentManager

import dev.rubentxu.pipeline.model.repository.SourceCodeRepositoryManager

@PipelineComponent
class AgentManager(
    val sourceCodeRepositoryManager: SourceCodeRepositoryManager,
    val logger: ILogger
): IAgentManager {


}