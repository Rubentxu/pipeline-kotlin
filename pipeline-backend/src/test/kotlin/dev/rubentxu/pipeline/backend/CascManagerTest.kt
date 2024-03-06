package dev.rubentxu.pipeline.backend


import dev.rubentxu.pipeline.backend.cdi.CascManager
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Paths

class CascManagerTest : StringSpec({
    val cascManager = CascManager()

    "test resolveConfig with valid yaml file" {
        val path = Paths.get("src/test/resources/casc/no-exist.yaml")
        val result = cascManager.resolvePipelineContext(path)

        result.isFailure shouldBe true
        // Aquí puedes agregar más aserciones para verificar que los datos en el resultado son los esperados
    }

    "test getRawConfig with valid yaml file" {
        val path = Paths.get("src/test/resources/casc/pipeline.yaml")
        val result = cascManager.getRawConfig(path)
        result.isSuccess shouldBe true
        val propertySet = result.getOrThrow()

        val pipeline = propertySet.get("pipeline")

        propertySet.entries.size shouldBe 1
        pipeline shouldNotBe null


        // Aquí puedes agregar más aserciones para verificar que los datos en el resultado son los esperados
    }


})

