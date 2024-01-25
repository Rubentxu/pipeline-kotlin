package dev.rubentxu.pipeline.backend.factories


import arrow.core.raise.Raise
import dev.rubentxu.pipeline.backend.factories.credentials.CredentialsProviderFactory
import dev.rubentxu.pipeline.backend.factories.jobs.JobInstanceFactory
import dev.rubentxu.pipeline.backend.jobs.JobInstance
import dev.rubentxu.pipeline.backend.mapper.PropertySet
import dev.rubentxu.pipeline.model.IPipelineContext
import dev.rubentxu.pipeline.model.PipelineContext
import dev.rubentxu.pipeline.model.PipelineDomain
import dev.rubentxu.pipeline.model.PropertiesError
import dev.rubentxu.pipeline.model.credentials.ICredentialsProvider

class PipelineContextFactory : PipelineDomain {
    companion object  {
        context(Raise<PropertiesError>)
        suspend fun create(data: PropertySet): IPipelineContext {
            val context = PipelineContext()

            val job = JobInstanceFactory.create(data)
            val credentials = CredentialsProviderFactory.create(data)

            context.registerService(JobInstance::class, job)
            context.registerService(ICredentialsProvider::class, credentials)

            return context
        }
    }
}

