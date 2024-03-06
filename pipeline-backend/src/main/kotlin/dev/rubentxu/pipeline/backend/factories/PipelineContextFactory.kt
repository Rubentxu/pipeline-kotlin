package dev.rubentxu.pipeline.backend.factories

import dev.rubentxu.pipeline.backend.factories.credentials.CredentialsProviderFactory
import dev.rubentxu.pipeline.backend.factories.jobs.JobInstanceFactory
import dev.rubentxu.pipeline.backend.jobs.JobInstance
import dev.rubentxu.pipeline.backend.mapper.PropertySet
import dev.rubentxu.pipeline.model.*
import dev.rubentxu.pipeline.model.credentials.ICredentialsProvider

import kotlin.Result

class PipelineContextFactory : PipelineDomain {
    companion object {
        suspend fun create(data: PropertySet): Result<IPipelineContext> = runCatching {
                val context = PipelineContext()
                val job = JobInstanceFactory.create(data).getOrThrow()
                val credentials = CredentialsProviderFactory.create(data).getOrThrow()
                context.registerService(JobInstance::class, job)
                context.registerService(ICredentialsProvider::class, credentials)
                context
            }
    }
}