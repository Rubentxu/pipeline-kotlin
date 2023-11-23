package dev.rubentxu.pipeline.casc

import pipeline.kotlin.extensions.deserializeYamlFileToMap
import dev.rubentxu.pipeline.validation.validateAndGet
import pipeline.kotlin.extensions.resolveValueExpressions
import java.nio.file.Path
import kotlin.Result.Companion.failure
import kotlin.Result.Companion.success


class CascManager {
    fun resolveConfig(path: Path): Result<PipelineConfig> {
        try {
            val yamlResult:Result<Map<String,Any>> = path.deserializeYamlFileToMap()
            if (yamlResult.isFailure) return failure(yamlResult.exceptionOrNull()!!)
            val rawYaml = yamlResult.getOrThrow()
            val resolvedYaml  = rawYaml.resolveValueExpressions()
            val config = PipelineConfig.fromMap(resolvedYaml)
            return success(config)
        } catch (e: Exception) {
            return failure(e)
        }
    }
}

class PipelineConfig(
    val credentials: Credentials?,
    val clouds: List<Cloud>?,


    ) {
    companion object {
        fun fromMap(map: Map<*, *>): PipelineConfig {
            val credentialsMap = resolveCredentialsMap(map)

            val cloudsMap: List<Map<String, Any>> = map.validateAndGet("pipeline.clouds")
                .isList().defaultValueIfInvalid(emptyList<Map<String, Any>>()) as List<Map<String, Any>>


            val cloudList: List<Cloud> = cloudsMap.map {
                return@map Cloud.fromMap(it)
            }

            return PipelineConfig(
                credentials = Credentials.fromMap(credentialsMap),
                clouds = cloudList
            )
        }

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
    }
}

data class Credentials(val credentials: List<Credential>) {
    companion object {
        fun fromMap(credentialsMap: List<Map<String, Any>>): Credentials? {
            if (credentialsMap.isEmpty()) return null
            val credentialList = credentialsMap.map {
                return@map Credential.fromMap(it)
            }
            return Credentials(credentialList)
        }
    }
}


data class DomainCredential(
    val credentials: List<Credential>
)

data class Cloud(
    val docker: DockerCloudConfig?,
    val kubernetes: KubernetesConfig?
) {
    companion object {
        fun fromMap(map: Map<String, Any>): Cloud {

            return Cloud(
                docker = if (map.containsKey("docker")) DockerCloudConfig.fromMap(map) else null,
                kubernetes = if (map.containsKey("kubernetes")) KubernetesConfig.fromMap(map) else null
            )
        }
    }
}



