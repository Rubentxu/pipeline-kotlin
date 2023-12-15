package dev.rubentxu.pipeline.backend.factories

import dev.rubentxu.pipeline.backend.jobs.JobInstance
import dev.rubentxu.pipeline.model.PipelineComponentFromMapFactory
import dev.rubentxu.pipeline.model.jobs.*
import dev.rubentxu.pipeline.model.steps.EnvVars
import dev.rubentxu.pipeline.model.validations.validateAndGet
import kotlinx.coroutines.Job

class JobBuilder {
    companion object : PipelineComponentFromMapFactory<Job> {
        override fun create(data: Map<String, Any>): Job {
            val envMap = data.validateAndGet("job.environmentVars").isMap()
                .throwIfInvalid("environmentVars is required in Job") as Map<String, String>
            val envVars = EnvVars(envMap)

            val trigger = data.validateAndGet("job.trigger").isMap().defaultValueIfInvalid(emptyMap<String,Any>())
            var cron: CronTrigger? = null
            if(trigger.containsKey("cron")) {
                cron = CromTriggerBuilder.create(data)
            }

            val parameters =
                data.validateAndGet("job.parameters")
                    .isList()
                    .defaultValueIfInvalid(emptyList<Map<String,Any>>())
                    .map { JobParameterFactory.create(it) }


            return JobInstance(
                name = data.validateAndGet("name").isString().throwIfInvalid("name is required in Job"),

                environmentVars = envVars,
                publisher = Publisher.create(
                    data.validateAndGet("publisher").isMap()
                        .throwIfInvalid("publisher is required in Job") as Map<String, Any>
                ),
                projectSource = ProjectSource.create(
                    data.validateAndGet("projectSource").isMap()
                        .throwIfInvalid("projectSource is required in Job") as Map<String, Any>
                ),
                librarySources = (data.validateAndGet("librarySources").isList()
                    .throwIfInvalid("librarySources is required in Job") as List<Map<String, Any>>).map { LibrarySource.create(it) },
                trigger = cron,
                pipelineFileSource = PipelineFileSourceCodeFactory.create(data),
                parameters = parameters,
            )
        }
    }
}

class JobParameterFactory {
    companion object : PipelineComponentFromMapFactory<JobParameter<*>> {
        override fun create(data: Map<String, Any>): JobParameter<*> {
            return when (data?.keys?.first()) {
                "string" -> StringJobParameter.create(data.get(data?.keys?.first()) as Map<String, Any>)
                "choice" -> ChoiceJobParameter.create(data.get(data?.keys?.first()) as Map<String, Any>)
                "boolean" -> BooleanJobParameter.create(data.get(data?.keys?.first()) as Map<String, Any>)
                "password" -> PasswordJobParameter.create(data.get(data?.keys?.first()) as Map<String, Any>)
                "text" -> TextJobParameter.create(data.get(data?.keys?.first()) as Map<String, Any>)
                else -> {
                    throw IllegalArgumentException("Invalid parameter type for '${data?.keys?.first()}'")
                }
            }
        }
    }
}