package dev.rubentxu.pipeline.backend.factories.jobs

import dev.rubentxu.pipeline.backend.factories.PipelineDomainFactory
import dev.rubentxu.pipeline.backend.factories.sources.PipelineFileSourceCodeFactory
import dev.rubentxu.pipeline.backend.factories.sources.PluginsDefinitionSourceFactory
import dev.rubentxu.pipeline.backend.factories.sources.ProjectSourceCodeFactory
import dev.rubentxu.pipeline.core.jobs.JobInstance
import dev.rubentxu.pipeline.backend.mapper.PropertySet
import dev.rubentxu.pipeline.backend.mapper.required
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope


class JobInstanceFactory {

    companion object : PipelineDomainFactory<JobInstance> {
        override val rootPath: String = "pipeline"

        override suspend fun create(data: PropertySet): Result<JobInstance> {
            val pipeline = getRootPropertySet(data)
            return coroutineScope {
                val name = async { pipeline.required<String>("name").getOrThrow() }
                val project = async { ProjectSourceCodeFactory.create(pipeline).getOrThrow() }
                val plugins = async { PluginsDefinitionSourceFactory.create(pipeline).getOrThrow() }
                val pipelineSourceCode = async { PipelineFileSourceCodeFactory.create(pipeline).getOrThrow() }
                val parameters = async { JobParameterFactory.create(pipeline).getOrThrow() }

                runCatching {
                    JobInstance(
                        name = name.await(),
                        projectSourceCode = project.await(),
                        pluginsSources = plugins.await(),
                        pipelineSourceCode = pipelineSourceCode.await(),
                        parameters = parameters.await()
                    )
                }
            }
        }
    }
}