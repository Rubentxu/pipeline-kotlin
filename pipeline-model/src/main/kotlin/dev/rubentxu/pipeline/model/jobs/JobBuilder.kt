package dev.rubentxu.pipeline.model.jobs

import dev.rubentxu.pipeline.model.PipelineComponentFromMapFactory
import dev.rubentxu.pipeline.steps.EnvVars
import dev.rubentxu.pipeline.validation.validateAndGet

class JobBuilder {
    companion object : PipelineComponentFromMapFactory<Job> {
        override fun create(data: Map<String, Any>): Job {
            val envMap = data.validateAndGet("environmentVars").isMap()
                .throwIfInvalid("environmentVars is required in Job") as Map<String, String>
            val envVars = EnvVars(envMap)

            val trigger = data.validateAndGet("trigger").isMap().defaultValueIfInvalid(emptyMap<String,Any>())
            var cron: CronTrigger? = null
            if(trigger.containsKey("cron")) {
                cron = CromTriggerBuilder.create(data)
            }

            data.validateAndGet("pipelineFileSource").isMap()
                .throwIfInvalid("pipelineFileSource is required in Job") as Map<String, Any>

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
                pipelineLoader =
            )
        }
    }
}