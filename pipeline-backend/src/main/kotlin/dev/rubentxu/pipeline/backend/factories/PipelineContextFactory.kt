package dev.rubentxu.pipeline.backend.factories


import dev.rubentxu.pipeline.backend.factories.credentials.CredentialsFactory
import dev.rubentxu.pipeline.backend.factories.jobs.JobInstanceFactory
import dev.rubentxu.pipeline.backend.factories.sources.SourceCodeRepositoryFactory
import dev.rubentxu.pipeline.backend.jobs.JobInstance
import dev.rubentxu.pipeline.model.IPipelineContext
import dev.rubentxu.pipeline.model.PipelineContext
import dev.rubentxu.pipeline.model.PipelineDomain
import dev.rubentxu.pipeline.model.PipelineDomainFactory
import dev.rubentxu.pipeline.model.validations.validateAndGet

class PipelineContextFactory : PipelineDomain {
        companion object : PipelineDomainFactory<IPipelineContext> {
            override val rootPath: String = "pipeline"
            override val instanceName: String = "PipelineContext"

            override suspend fun create(data: Map<String, Any>): IPipelineContext {
                val context = PipelineContext()

                val job = JobInstanceFactory.create(data)
                val repositories = SourceCodeRepositoryFactory.create(data)
                val credentials = CredentialsFactory.create(data)

                context.registerService(JobInstance::class, job)

                return context
            }
        }
}

