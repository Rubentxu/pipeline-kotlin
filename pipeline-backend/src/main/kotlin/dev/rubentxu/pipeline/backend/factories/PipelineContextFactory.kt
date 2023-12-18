package dev.rubentxu.pipeline.backend.factories


import dev.rubentxu.pipeline.backend.factories.agents.AgentsFactory
import dev.rubentxu.pipeline.model.PipelineContext
import dev.rubentxu.pipeline.model.PipelineDomainFactory
import dev.rubentxu.pipeline.model.credentials.ICredentialsProvider
import dev.rubentxu.pipeline.model.logger.IPipelineLogger
import dev.rubentxu.pipeline.model.repository.SourceCodeRepositoryManager
import dev.rubentxu.pipeline.model.steps.EnvVars
import dev.rubentxu.pipeline.model.validations.validateAndGet

class PipelineContextFactory(
    val credentialsProvider: ICredentialsProvider,
    val sourceCodeRepositoryManager: SourceCodeRepositoryManager,
    val logger: IPipelineLogger,
) : PipelineDomainFactory<PipelineContext> {

    override fun create(data: Map<String, Any>): PipelineContext {

        val jobMap = data.validateAndGet("pipeline.job")
            .isMap()
            .defaultValueIfInvalid(emptyMap<String, Any>()) as Map<String, Any>

        val job = JobInstanceFactory(
            sourceCodeRepositoryManager,
            logger)
            .create(jobMap)

        return PipelineContext(
            credentialsProvider = credentialsProvider,
            scm = sourceCodeRepositoryManager,
            environmentVars = EnvVars(data.mapValues { it.value.toString() }),
            agent =  AgentsFactory.create(data),
            job = job,
        )
    }
}

