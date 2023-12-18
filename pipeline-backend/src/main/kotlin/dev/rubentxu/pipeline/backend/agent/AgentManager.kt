package dev.rubentxu.pipeline.backend.agent


import dev.rubentxu.pipeline.model.agents.IAgentManager

import dev.rubentxu.pipeline.model.logger.PipelineLogger

import dev.rubentxu.pipeline.model.repository.SourceCodeRepositoryManager


class AgentManager(
    val sourceCodeRepositoryManager: SourceCodeRepositoryManager
): IAgentManager {
    val logger = PipelineLogger.getLogger()






}