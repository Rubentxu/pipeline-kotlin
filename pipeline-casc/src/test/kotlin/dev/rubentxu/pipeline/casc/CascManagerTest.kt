package dev.rubentxu.pipeline.casc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

class CascManagerTest : StringSpec({
    "resolveConfig should correctly deserialize YAML to PipelineConfig" {          // Crear una instancia de CascManager
        val cascManager = CascManager()

        // Ruta al archivo YAML de prueba
        val resourcePath = this::class.java.classLoader.getResource("casc/credentials.yaml").path
        val testYamlPath = Path.of(resourcePath)

        // Ejecutar resolveConfig
        val result = cascManager.resolveConfig(testYamlPath)

        // Comprobar que el resultado sea el esperado
        result.isSuccess shouldBe true
        result.getOrThrow().credentials?.credentials?.size shouldBe 8
    }

})
