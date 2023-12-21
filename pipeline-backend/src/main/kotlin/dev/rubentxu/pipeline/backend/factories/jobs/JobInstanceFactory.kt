package dev.rubentxu.pipeline.backend.factories.jobs

import dev.rubentxu.pipeline.model.PipelineDomainFactory
import dev.rubentxu.pipeline.model.jobs.*
import dev.rubentxu.pipeline.model.validations.validateAndGet

import dev.rubentxu.pipeline.backend.jobs.JobInstance

class JobInstanceFactory : PipelineDomainFactory<JobInstance> {
    override suspend fun create(data: Map<String, Any>): JobInstance {
        val name = data.validateAndGet("name").isString().throwIfInvalid("name is required in JobInstance")
        val publisher = PublisherFactory.create(data.validateAndGet("publisher").isMap().throwIfInvalid("publisher is required in JobInstance") as Map<String, Any>)
        val projectSourceCode = SourceCodeConfigFactory.create(data.validateAndGet("projectSourceCode").isMap().throwIfInvalid("projectSourceCode is required in JobInstance") as Map<String, Any>)
        val pluginsSources = (data.validateAndGet("pluginsSources").isList().throwIfInvalid("pluginsSources is required in JobInstance") as List<Map<String, Any>>).map { SourceCodeConfigFactory.create(it) }
        val pipelineSourceCode = SourceCodeConfigFactory.create(data.validateAndGet("pipelineSourceCode").isMap().throwIfInvalid("pipelineSourceCode is required in JobInstance") as Map<String, Any>)
        val trigger = TriggerFactory.create(data.validateAndGet("trigger").isMap().defaultValueIfInvalid(emptyMap<String, Any>()) as Map<String, Any>)
        val parameters = (data.validateAndGet("parameters").isList().defaultValueIfInvalid(emptyList<Map<String, Any>>()) as List<Map<String, Any>>).map { JobParameterFactory.create(it) }

        return JobInstance(
            name = name,
            publisher = publisher,
            projectSourceCode = projectSourceCode,
            pluginsSources = pluginsSources,
            pipelineSourceCode = pipelineSourceCode,
            trigger = trigger,
            parameters = parameters
        )
    }
}