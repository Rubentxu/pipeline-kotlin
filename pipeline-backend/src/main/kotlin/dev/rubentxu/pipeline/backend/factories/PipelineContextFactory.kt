package dev.rubentxu.pipeline.backend.factories


import arrow.core.raise.fold
import dev.rubentxu.pipeline.backend.factories.credentials.CredentialsProviderFactory
import dev.rubentxu.pipeline.backend.factories.jobs.JobInstanceFactory
import dev.rubentxu.pipeline.backend.jobs.JobInstance
import dev.rubentxu.pipeline.backend.mapper.PropertySet
import dev.rubentxu.pipeline.model.*
import dev.rubentxu.pipeline.model.credentials.ICredentialsProvider

class PipelineContextFactory : PipelineDomain {
    companion object {

        suspend fun create(data: PropertySet): Result<IPipelineContext> {
            return fold(
                block = {
                    val context = PipelineContext()
                    val job = JobInstanceFactory.create(data).bind()
                    val credentials = CredentialsProviderFactory.create(data).bind()
                    context.registerService(JobInstance::class, job)
                    context.registerService(ICredentialsProvider::class, credentials)
                    context
                },
                recover = { error: PipelineError -> Result.failure(PipelineException("Error creating pipeline context ${error.message}}")) },
                transform = { context: PipelineContext -> Result.success(context) }
            )
        }
    }
}

