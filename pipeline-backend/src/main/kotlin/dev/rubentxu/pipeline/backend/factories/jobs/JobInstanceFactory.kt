package dev.rubentxu.pipeline.backend.factories.jobs

import dev.rubentxu.pipeline.backend.coroutines.parZipResult
import dev.rubentxu.pipeline.backend.factories.PipelineDomainFactory
import dev.rubentxu.pipeline.backend.factories.sources.PipelineFileSourceCodeFactory
import dev.rubentxu.pipeline.backend.factories.sources.PluginsDefinitionSourceFactory
import dev.rubentxu.pipeline.backend.factories.sources.ProjectSourceCodeFactory
import dev.rubentxu.pipeline.backend.jobs.JobInstance
import dev.rubentxu.pipeline.backend.mapper.PropertySet
import dev.rubentxu.pipeline.backend.mapper.required


class JobInstanceFactory {

    companion object : PipelineDomainFactory<JobInstance> {
        override val rootPath: String = "pipeline"

        override suspend fun create(data: PropertySet): Result<JobInstance> {
            val pipeline = getRootPropertySet(data)
            return parZipResult(
                { pipeline.required<String>("name") },
                { ProjectSourceCodeFactory.create(pipeline) },
                { PluginsDefinitionSourceFactory.create(pipeline) },
                { PipelineFileSourceCodeFactory.create(pipeline) },
                { JobParameterFactory.create(pipeline) }

            ) { name, project, plugins, pipelineSourceCode, parameters ->
                JobInstance(
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