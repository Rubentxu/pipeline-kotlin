package dev.rubentxu.pipeline.model

import dev.rubentxu.pipeline.model.agents.AgentConfig
import dev.rubentxu.pipeline.model.agents.DockerCloudConfig
import dev.rubentxu.pipeline.model.agents.KubernetesConfig
import dev.rubentxu.pipeline.model.credentials.ICredentialsProvider
import dev.rubentxu.pipeline.model.credentials.LocalCredentialsProvider
import dev.rubentxu.pipeline.model.jobs.JobDefinition
import dev.rubentxu.pipeline.model.repository.SourceCodeRepositoryManager
import dev.rubentxu.pipeline.model.steps.EnvVars
import dev.rubentxu.pipeline.model.validations.validateAndGet


interface PipelineComponent

interface IPipelineConfig: PipelineComponent

interface PipelineComponentBuider<D, T: PipelineComponent>  {
    fun create(data: D): T?
}

interface PipelineComponentFromMapFactory<T: PipelineComponent> : PipelineComponentBuider<Map<String, Any>,T> {
    override fun create(data: Map<String, Any>): T?
}
interface PipelineComponentFromListFactory <T: PipelineComponent> : PipelineComponentBuider<List<Map<String, Any>>,T> {
    override fun create(data: List<Map<String, Any>>): T?
}

data class IDComponent private constructor(
    val id: String,
)  {
    companion object {
        fun create(id: String): IDComponent {
            require(id.isNotEmpty()) { "ID cannot be empty" }
            require(id.length <= 50) { "ID cannot be more than 50 characters" }
            require(id.all { it.isDefined() }) { "ID can only contain alphanumeric characters : ${id}" }
            return IDComponent(id)
        }
    }

    override fun toString(): String {
        return id
    }
}


data class PipelineContext(
    val credentialsProvider: ICredentialsProvider,
    val clouds: List<Cloud>?,
    val scm: SourceCodeRepositoryManager?,
    val globalLibraries: GlobalLibrariesConfig,
    val environmentVars: EnvVars,
    val agents: List<AgentConfig>,
    val job: JobDefinition?,

    ) : IPipelineConfig {
    companion object : PipelineComponentFromMapFactory<PipelineContext> {
        private fun resolveCredentialsMap(map: Map<*, *>): List<Map<String, Any>> {
            val domainCredentials = map.validateAndGet("credentials.system.domainCredentials")
                .isList().defaultValueIfInvalid(emptyList<Map<String, Any>>()) as List<Map<String, Any>>

            val credentialsMap = if (domainCredentials.isEmpty()) {
                emptyList<Map<String, Any>>()
            } else {
                domainCredentials?.get(0)?.validateAndGet("credentials")
                    ?.isList()?.defaultValueIfInvalid(emptyList<Map<String, Any>>()) as List<Map<String, Any>>
            }

            return credentialsMap
        }


        override fun create(data: Map<String, Any>): PipelineContext {
            val credentialsMap = resolveCredentialsMap(data)

            val cloudsMap: List<Map<String, Any>> = data.validateAndGet("pipeline.clouds")
                .isList().defaultValueIfInvalid(emptyList<Map<String, Any>>()) as List<Map<String, Any>>


            val cloudList: List<Cloud> = cloudsMap.map {
                return@map Cloud.create(it)
            }


            val agentsMap: List<Map<String, Any>> = data.validateAndGet("pipeline.agents")
                .isList().defaultValueIfInvalid(emptyList<Map<String, Any>>()) as List<Map<String, Any>>

            val agentsList: List<AgentConfig> = agentsMap.map {
                return@map AgentConfig.create(it)
            }

            val jobMap = data.validateAndGet("pipeline.job")
                .isMap()
                .defaultValueIfInvalid(emptyMap<String, Any>()) as Map<String, Any>



            return PipelineContext(
                credentialsProvider =  LocalCredentialsProvider.create(credentialsMap),
                clouds = cloudList,
                scm = null,
                globalLibraries = GlobalLibrariesConfig.create(data),
                environmentVars = EnvVars(data.mapValues { it.value.toString() }),
                agents = agentsList,
                job = null,
            )
        }
    }
}



data class Cloud(
    val docker: DockerCloudConfig?,
    val kubernetes: KubernetesConfig?,
) : PipelineComponent {
    companion object : PipelineComponentFromMapFactory<Cloud> {
        override fun create(data: Map<String, Any>): Cloud {
            return Cloud(
                docker = if (data.containsKey("docker")) DockerCloudConfig.create(data) else null,
                kubernetes = if (data.containsKey("kubernetes")) KubernetesConfig.create(data) else null
            )
        }
    }

}



