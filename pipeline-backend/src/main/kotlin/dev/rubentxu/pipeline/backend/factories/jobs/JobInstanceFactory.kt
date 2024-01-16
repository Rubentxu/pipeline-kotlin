package dev.rubentxu.pipeline.backend.factories.jobs

import arrow.core.raise.Raise
import arrow.fx.coroutines.parZip
import dev.rubentxu.pipeline.backend.factories.sources.PipelineFileSourceCodeFactory
import dev.rubentxu.pipeline.backend.factories.sources.PluginsDefinitionSourceFactory
import dev.rubentxu.pipeline.backend.factories.sources.ProjectSourceCodeFactory
import dev.rubentxu.pipeline.backend.jobs.JobInstance
import dev.rubentxu.pipeline.model.PipelineDomainFactory
import dev.rubentxu.pipeline.model.mapper.*

class JobInstanceFactory {

    context(Raise<ValidationError>)
    companion object : PipelineDomainFactory<JobInstance> {
        override val rootPath: PropertyPath = "pipeline".propertyPath()

        context(Raise<ValidationError>)
        override suspend fun create(data: PropertySet): JobInstance {
            val pipeline = getRootPropertySet(data)
            return parZip(
                { pipeline.required<String>("name") },
                { ProjectSourceCodeFactory.create(pipeline) },
                { PluginsDefinitionSourceFactory.create(pipeline) },
                { PipelineFileSourceCodeFactory.create(pipeline) },
                { JobParameterFactory.create(pipeline) }

            ) { name, project, plugins, pipelineSourceCode, parameters ->
                return@parZip JobInstance(
                    name = name,
                    projectSourceCode = project,
                    pluginsSources = plugins,
                    pipelineSourceCode = pipelineSourceCode,
                    parameters = parameters
                )
            }
        }
    }
}