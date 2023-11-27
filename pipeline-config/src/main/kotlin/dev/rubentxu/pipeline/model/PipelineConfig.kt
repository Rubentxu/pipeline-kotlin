package dev.rubentxu.pipeline.model

import dev.rubentxu.pipeline.steps.EnvVars
import pipeline.kotlin.extensions.deserializeYamlFileToMap
import dev.rubentxu.pipeline.validation.validateAndGet
import pipeline.kotlin.extensions.resolveValueExpressions
import java.nio.file.Path
import kotlin.Result.Companion.failure
import kotlin.Result.Companion.success


interface Configuration {

}

interface ConfigurationBuider

interface MapConfigurationBuilder<T: Configuration> : ConfigurationBuider {
    fun build(data: Map<String, Any>): T?
}

interface ListMapConfigurationBuilder <T: Configuration> : ConfigurationBuider {
    fun build(data: List<Map<String, Any>>): T?
}



class CascManager {
    fun resolveConfig(path: Path): Result<PipelineConfig> {
        try {
            val yamlResult:Result<Map<String,Any>> = path.deserializeYamlFileToMap()
            if (yamlResult.isFailure) return failure(yamlResult.exceptionOrNull()!!)
            val rawYaml = yamlResult.getOrThrow()
            val resolvedYaml : Map<String, Any>  = rawYaml.resolveValueExpressions() as Map<String, Any>
            val config = PipelineConfig.build(resolvedYaml)
            return success(config)
        } catch (e: Exception) {
            return failure(e)
        }
    }
}

data class PipelineConfig(
    val credentials: Credentials?,
    val clouds: List<Cloud>?,
    val scm: ScmConfig,
    val globalLibraries: GlobalLibrariesConfig,
    val environmentVars: EnvVars

): Configuration {
    companion object: MapConfigurationBuilder<PipelineConfig> {
        private fun resolveCredentialsMap(map: Map<*, *>): List<Map<String, Any>> {
            val domainCredentials = map.validateAndGet("credentials.system.domainCredentials")
                .isList().defaultValueIfInvalid(emptyList<Map<String, Any>>()) as List<Map<String, Any>>

            val credentialsMap = if(domainCredentials.isEmpty()) {
                emptyList<Map<String, Any>>()
            } else {
                domainCredentials?.get(0)?.validateAndGet("credentials")
                    ?.isList()?.defaultValueIfInvalid(emptyList<Map<String, Any>>()) as List<Map<String, Any>>
            }

            return credentialsMap
        }


        override fun  build(data: Map<String, Any>): PipelineConfig {
            val credentialsMap = resolveCredentialsMap(data)

            val cloudsMap: List<Map<String, Any>> = data.validateAndGet("pipeline.clouds")
                .isList().defaultValueIfInvalid(emptyList<Map<String, Any>>()) as List<Map<String, Any>>


            val cloudList: List<Cloud> = cloudsMap.map {
                return@map Cloud.build(it)
            }

            return PipelineConfig(
                credentials = Credentials.build(credentialsMap),
                clouds = cloudList,
                scm = ScmConfig.fromMap(data),
                globalLibraries = GlobalLibrariesConfig.build(data),
                environmentVars = EnvVars(data.mapValues { it.value.toString() } )
            )
        }
    }
}

data class Credentials(val credentials: List<Credential>): Configuration {
    companion object: ListMapConfigurationBuilder<Credentials> {
        override fun build(data: List<Map<String, Any>>): Credentials? {
            if (data.isEmpty()) return null

            val credentialList: List<Credential> = data.map {
                return@map Credential.build(it)
            }
            return Credentials(credentialList)
        }
    }
}



data class Cloud(
    val docker: DockerCloudConfig?,
    val kubernetes: KubernetesConfig?
): Configuration {
    companion object: MapConfigurationBuilder<Cloud> {
        override fun build(data: Map<String, Any>): Cloud {
            return Cloud(
                docker = if (data.containsKey("docker")) DockerCloudConfig.build(data) else null,
                kubernetes = if (data.containsKey("kubernetes")) KubernetesConfig.build(data) else null
            )
        }
    }

}



