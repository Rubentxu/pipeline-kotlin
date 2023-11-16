package dev.rubentxu.pipeline.casc

import dev.rubentxu.pipeline.casc.resolver.SecretSourceResolver
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path



class PipelineConfigTest : StringSpec({

    "PipelineConfig fromMap" {
        // Crear una instancia de SecretSourceResolver
        val map = resolveConfig("casc/credentials.yaml")
        PipelineConfig.fromMap(map!!) shouldBe PipelineConfig(
            credentials = null,
            clouds = null
        )
    }



})

fun resolveConfig(yamlFileName: String): Map<*,*>? {
    val secretSourceResolver = SecretSourceResolver()

    // Crear una instancia de CascManager
    val cascManager = CascManager(secretSourceResolver)

    // Ruta al archivo YAML de prueba
    val resourcePath = PipelineConfigTest::class.java.classLoader.getResource(yamlFileName).path
    val testYamlPath = Path.of(resourcePath)

    // Ejecutar resolveConfig
    return cascManager.resolveConfig(testYamlPath)
}