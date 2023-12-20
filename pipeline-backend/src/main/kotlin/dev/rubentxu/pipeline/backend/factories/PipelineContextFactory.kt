package dev.rubentxu.pipeline.backend.factories


import dev.rubentxu.pipeline.backend.jobs.JobInstance
import dev.rubentxu.pipeline.model.IPipelineContext
import dev.rubentxu.pipeline.model.PipelineContext
import dev.rubentxu.pipeline.model.PipelineDomain
import dev.rubentxu.pipeline.model.PipelineDomainFactory
import dev.rubentxu.pipeline.model.validations.validateAndGet

class PipelineContextFactory : PipelineDomain {
        companion object : PipelineDomainFactory<IPipelineContext> {
            override suspend fun create(data: Map<String, Any>): IPipelineContext {
                val context = PipelineContext()
                val jobMap = data.validateAndGet("pipeline.job")
                    .isMap()
                    .defaultValueIfInvalid(emptyMap<String, Any>()) as Map<String, Any>

                val job = JobInstanceFactory.create(jobMap)

                context.registerService(JobInstance::class, job)

                return context
            }
        }
}

