package dev.rubentxu.pipeline.backend.factories.jobs

import arrow.core.raise.either
import arrow.fx.coroutines.parZip
import dev.rubentxu.pipeline.backend.factories.PipelineDomainFactory
import dev.rubentxu.pipeline.backend.factories.sources.PipelineFileSourceCodeFactory
import dev.rubentxu.pipeline.backend.factories.sources.PluginsDefinitionSourceFactory
import dev.rubentxu.pipeline.backend.factories.sources.ProjectSourceCodeFactory
import dev.rubentxu.pipeline.backend.jobs.JobInstance
import dev.rubentxu.pipeline.backend.mapper.PropertySet
import dev.rubentxu.pipeline.backend.mapper.required
import dev.rubentxu.pipeline.model.Res

class JobInstanceFactory {

    companion object : PipelineDomainFactory<JobInstance> {
        override val rootPath: String = "pipeline"

        override suspend fun create(data: PropertySet): Res<JobInstance> =
            either {
                val pipeline = getRootPropertySet(data)
                parZip(
                    { pipeline.required<String>("name") },
                    { ProjectSourceCodeFactory.create(pipeline).bind() },
                    { PluginsDefinitionSourceFactory.create(pipeline).bind() },
                    { PipelineFileSourceCodeFactory.create(pipeline).bind() },
                    { JobParameterFactory.create(pipeline).bind() }

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