package dev.rubentxu.pipeline.model

import dev.rubentxu.pipeline.model.agents.Agent
import dev.rubentxu.pipeline.model.agents.DockerAgent
import dev.rubentxu.pipeline.model.agents.KubernetesAgent
import dev.rubentxu.pipeline.model.credentials.ICredentialsProvider
import dev.rubentxu.pipeline.model.jobs.JobDefinition
import dev.rubentxu.pipeline.model.repository.SourceCodeRepositoryManager
import dev.rubentxu.pipeline.model.steps.EnvVars

interface PipelineDomain

interface IPipelineConfig: PipelineDomain

interface PipelineDomainFactory<T: PipelineDomain>  {
    fun create(data: Map<String, Any>): T?
}

interface PipelineDomainDslFactory<T: PipelineDomain> {
    fun create(block: T.() -> Unit): PipelineDomain
}

data class IDComponent private constructor(
    val id: String,
)  {
    companion object {
        fun create(id: String): IDComponent {
            require(id.isNotEmpty()) { "ID cannot be empty" }
            require(id.length <= 50) { "ID cannot be more than 50 characters" }
            require(id.all { it.isDefined() }) { "ID can only contain alphanumeric characters : ${id}" }
            return IDComponent(id)
        }
    }

    override fun toString(): String {
        return id
    }
}

data class PipelineContext(
    val credentialsProvider: ICredentialsProvider,
    val agent: Agent,
    val scm: SourceCodeRepositoryManager,
    val environmentVars: EnvVars,
    val job: JobDefinition,
    ) : IPipelineConfig





