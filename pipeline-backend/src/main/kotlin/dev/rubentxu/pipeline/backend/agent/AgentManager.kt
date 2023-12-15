package dev.rubentxu.pipeline.backend.agent

import dev.rubentxu.pipeline.backend.buildPipeline
import dev.rubentxu.pipeline.backend.evaluateScriptFile
import dev.rubentxu.pipeline.model.agents.IAgentManager
import dev.rubentxu.pipeline.model.jobs.*
import dev.rubentxu.pipeline.model.logger.PipelineLogger
import dev.rubentxu.pipeline.model.pipeline.Pipeline
import dev.rubentxu.pipeline.model.repository.SourceCodeRepositoryManager
import java.net.URL
import java.nio.file.Path

class AgentManager(
    val sourceCodeRepositoryManager: SourceCodeRepositoryManager
): IAgentManager {
    val logger = PipelineLogger.getLogger()






}