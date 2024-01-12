package dev.rubentxu.pipeline.backend.factories.jobs

import dev.rubentxu.pipeline.backend.factories.sources.PipelineFileSourceCodeFactory
import dev.rubentxu.pipeline.backend.factories.sources.PluginsDefinitionSourceFactory
import dev.rubentxu.pipeline.backend.factories.sources.ProjectSourceCodeFactory
import dev.rubentxu.pipeline.backend.jobs.JobInstance
import dev.rubentxu.pipeline.model.PipelineDomainFactory
import dev.rubentxu.pipeline.model.mapper.PropertySet
import dev.rubentxu.pipeline.model.validations.validateAndGet
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class JobInstanceFactory {
    companion object : PipelineDomainFactory<JobInstance> {
        override val rootPath: String = "pipeline"
        override val instanceName: String = "JobInstance"

        override suspend fun create(data: PropertySet): JobInstance {
            val pipelineMap = getRootPropertySet(data)

            return coroutineScope {
                val name = pipelineMap.validateAndGet("name")
                    .isString()
                    .throwIfInvalid(getErrorMessage("name"))

                val deferedProjectSourceCode = async { ProjectSourceCodeFactory.create(data) }
                val deferedPluginsSources = async { PluginsDefinitionSourceFactory.create(data) }
                val deferedPipelineSourceCode = async { PipelineFileSourceCodeFactory.create(data) }
                val deferedJobParameters = async { JobParameterFactory.create(data) }

                val projectSourceCode = deferedProjectSourceCode.await()
                val pluginsSources = deferedPluginsSources.await().list
                val pipelineSourceCode = deferedPipelineSourceCode.await()
                val jobParameters = deferedJobParameters.await().list

                return@coroutineScope JobInstance(
                    name = name,
                    projectSourceCode = projectSourceCode,
                    pluginsSources = pluginsSources,
                    pipelineSourceCode = pipelineSourceCode,
                    parameters = jobParameters
                )
            }

        }
    }
}