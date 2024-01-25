package dev.rubentxu.pipeline.backend

import arrow.core.raise.either
import dev.rubentxu.pipeline.backend.cdi.CascManager
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldNotBe
import java.nio.file.Paths

class CascManagerTest : StringSpec({
    val cascManager = CascManager()

    "test resolveConfig with valid yaml file" {
        val path = Paths.get("src/test/resources/casc/pipeline.yaml")
        val result = either {
            cascManager.resolveConfig(path)
        }
        result shouldNotBe null
        // Aquí puedes agregar más aserciones para verificar que los datos en el resultado son los esperados
    }

    "test getRawConfig with valid yaml file" {
        val path = Paths.get("src/test/resources/casc/pipeline.yaml")
        val result = either {
            cascManager.getRawConfig(path)
        }
        result shouldNotBe null
        // Aquí puedes agregar más aserciones para verificar que los datos en el resultado son los esperados
    }


})

