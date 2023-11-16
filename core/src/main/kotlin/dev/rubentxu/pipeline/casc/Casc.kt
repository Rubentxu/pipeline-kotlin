package dev.rubentxu.pipeline.casc

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import dev.rubentxu.pipeline.casc.resolver.SecretSourceResolver
import dev.rubentxu.pipeline.validation.validate
import dev.rubentxu.pipeline.validation.validateAndGet
import java.nio.file.Path

class CascYamlDeserializer(private val substitutor: SecretSourceResolver) : JsonDeserializer<String>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): String {
        val rawValue = p.valueAsString
        return substitutor.resolve(rawValue)
    }
}
class CascManager(val secretSourceResolver: SecretSourceResolver) {
    val module = SimpleModule().addDeserializer(String::class.java, CascYamlDeserializer(secretSourceResolver))
    val mapper = ObjectMapper(YAMLFactory()).registerModule(module).registerKotlinModule()

    fun resolveConfig(path: Path): Map<*,*> {
        val rawYaml = path.toFile().readText()
        return mapper.readValue(rawYaml, Map::class.java)
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

            if(domainCredentials.isEmpty()) throw IllegalArgumentException("No credentials found"
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



