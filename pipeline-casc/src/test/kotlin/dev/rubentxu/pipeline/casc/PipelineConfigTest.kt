package dev.rubentxu.pipeline.casc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

class PipelineConfigTest : StringSpec({

    "PipelineConfig fromMap" {
        val cascManager = CascManager()

        // Ruta al archivo YAML de prueba
        val resourcePath = PipelineConfigTest::class.java.classLoader.getResource("casc/credentials.yaml").path
        val testYamlPath = Path.of(resourcePath)


         val config = cascManager.resolveConfig(testYamlPath)

        config.isSuccess shouldBe true
        config.getOrThrow().credentials?.credentials?.size shouldBe 1


    }


})
