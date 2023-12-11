package dev.rubentxu.pipeline.model

import dev.rubentxu.pipeline.model.agents.AgentConfig
import dev.rubentxu.pipeline.model.agents.DockerCloudConfig
import dev.rubentxu.pipeline.model.agents.KubernetesConfig
import dev.rubentxu.pipeline.model.config.Configuration
import dev.rubentxu.pipeline.model.config.IPipelineConfig
import dev.rubentxu.pipeline.model.config.ListMapConfigurationBuilder
import dev.rubentxu.pipeline.model.config.MapConfigurationBuilder
import dev.rubentxu.pipeline.steps.EnvVars
import dev.rubentxu.pipeline.validation.validateAndGet
import pipeline.kotlin.extensions.deserializeYamlFileToMap
import pipeline.kotlin.extensions.resolveValueExpressions
import java.nio.file.Path
import kotlin.Result.Companion.failure
import kotlin.Result.Companion.success

class CascManager {
    fun resolveConfig(path: Path): Result<PipelineConfig> {
        try {
            val yamlResult: Result<Map<String, Any>> = path.deserializeYamlFileToMap()
            if (yamlResult.isFailure) return failure(yamlResult.exceptionOrNull()!!)
            val rawYaml = yamlResult.getOrThrow()
            val resolvedYaml: Map<String, Any> = rawYaml.resolveValueExpressions() as Map<String, Any>
            val config = PipelineConfig.build(resolvedYaml)
            return success(config)
        } catch (e: Exception) {
            return failure(e)
        }
    }
}

data class PipelineConfig(
    val credentialsConfig: CredentialsConfig?,
    val clouds: List<Cloud>?,
    val scm: ScmConfig,
    val globalLibraries: GlobalLibrariesConfig,
    val environmentVars: EnvVars,
    val agents: List<AgentConfig>,
    val jobs: List<JobConfig>,

    ) : IPipelineConfig {
    companion object : MapConfigurationBuilder<PipelineConfig> {
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


        override fun build(data: Map<String, Any>): PipelineConfig {
            val credentialsMap = resolveCredentialsMap(data)

            val cloudsMap: List<Map<String, Any>> = data.validateAndGet("pipeline.clouds")
                .isList().defaultValueIfInvalid(emptyList<Map<String, Any>>()) as List<Map<String, Any>>


            val cloudList: List<Cloud> = cloudsMap.map {
                return@map Cloud.build(it)
            }


            val agentsMap: List<Map<String, Any>> = data.validateAndGet("pipeline.agents")
                .isList().defaultValueIfInvalid(emptyList<Map<String, Any>>()) as List<Map<String, Any>>

            val agentsList: List<AgentConfig> = agentsMap.map {
                return@map AgentConfig.build(it)
            }

            val jobsMap: List<Map<String, Any>> = data.validateAndGet("pipeline.jobs")
                .isList().defaultValueIfInvalid(emptyList<Map<String, Any>>()) as List<Map<String, Any>>

            val jobsList: List<JobConfig> = jobsMap.map {
                return@map JobConfig.build(it)
            }

            return PipelineConfig(
                credentialsConfig = CredentialsConfig.build(credentialsMap),
                clouds = cloudList,
                scm = ScmConfig.build(data),
                globalLibraries = GlobalLibrariesConfig.build(data),
                environmentVars = EnvVars(data.mapValues { it.value.toString() }),
                agents = agentsList,
                jobs = jobsList
            )
        }
    }
}

data class CredentialsConfig(val credentialConfigs: List<CredentialConfig>) : Configuration {
    companion object : ListMapConfigurationBuilder<CredentialsConfig> {
        override fun build(data: List<Map<String, Any>>): CredentialsConfig? {
            if (data.isEmpty()) return null

            val credentialConfigLists: List<CredentialConfig> = data.map {
                return@map CredentialConfig.build(it)
            }
            return CredentialsConfig(credentialConfigLists)
        }
    }
}


data class Cloud(
    val docker: DockerCloudConfig?,
    val kubernetes: KubernetesConfig?,
) : Configuration {
    companion object : MapConfigurationBuilder<Cloud> {
        override fun build(data: Map<String, Any>): Cloud {
            return Cloud(
                docker = if (data.containsKey("docker")) DockerCloudConfig.build(data) else null,
                kubernetes = if (data.containsKey("kubernetes")) KubernetesConfig.build(data) else null
            )
        }
    }

}



