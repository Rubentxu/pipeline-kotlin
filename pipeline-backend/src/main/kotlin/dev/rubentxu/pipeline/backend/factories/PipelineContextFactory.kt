package dev.rubentxu.pipeline.backend.factories


import dev.rubentxu.pipeline.backend.factories.credentials.LocalCredentialsFactory
import dev.rubentxu.pipeline.backend.factories.jobs.JobInstanceFactory
import dev.rubentxu.pipeline.backend.factories.sources.SourceCodeRepositoryFactory
import dev.rubentxu.pipeline.backend.jobs.JobInstance
import dev.rubentxu.pipeline.model.IPipelineContext
import dev.rubentxu.pipeline.model.PipelineContext
import dev.rubentxu.pipeline.model.PipelineDomain
import dev.rubentxu.pipeline.model.PipelineDomainFactory

class PipelineContextFactory : PipelineDomain {
        companion object : PipelineDomainFactory<IPipelineContext> {
            override val rootPath: String = "pipeline"
            override val instanceName: String = "PipelineContext"

            override suspend fun create(data: Map<String, Any>): IPipelineContext {
                val context = PipelineContext()

                val job = JobInstanceFactory.create(data)
                val repositories = SourceCodeRepositoryFactory.create(data)
                val credentials = LocalCredentialsFactory.create(data)

                context.registerService(JobInstance::class, job)

                return context
            }
        }
}

