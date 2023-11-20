package dev.rubentxu.pipeline.casc


import dev.rubentxu.pipeline.casc.resolver.SecretSourceResolver
//import kotlinx.serialization.yaml.Yaml
import dev.rubentxu.pipeline.validation.validateAndGet
import java.nio.file.Path


class CascManager(val secretSourceResolver: SecretSourceResolver) {


    fun resolveConfig(path: Path): Map<*,*> {
        val rawYaml = path.toFile().readText()
        val resolvedYaml = secretSourceResolver.resolve(rawYaml)
//        return Yaml.decodeFromString<Map<*,*>>(resolvedYaml)
        return emptyMap<String, String>()
    }

}

class PipelineConfig(
    val credentials: Credentials?,
    val clouds: List<Cloud>?,



) {
    companion object {
        fun fromMap(map: Map<*, *>): PipelineConfig {
            val domainCredentials =  map.validateAndGet("credentials.system.domainCredentials")
                                                            .isList().defaultValueIfInvalid(emptyList<Map<String, Any>>()) as List<Map<String, Any>>

            if(domainCredentials.isEmpty()) throw IllegalArgumentException("No credentials found")
            val credentials = domainCredentials[0].validateAndGet("credentials")
                                                    .isList().defaultValueIfInvalid(emptyList<Map<String, Any>>()) as List<Map<String, Any>>


            credentials.forEach { map ->
                map.forEach { (key, value) ->
                    println("Clave: $key, Valor: $value")
                    // Si el valor es también un mapa o una lista, puedes recorrerlo de manera similar.

                }
            }
            return PipelineConfig(
                credentials = null,
                clouds = null
            )
        }
    }
}

data class Credentials(
    val system: SystemCredentials
)

data class SystemCredentials(
    val domainCredentials: List<DomainCredential>
)

data class DomainCredential(
    val credentials: List<Credential>
)

data class Cloud(
    val docker: DockerCloudConfig,
    val kubernetes: KubernetesConfig
)



