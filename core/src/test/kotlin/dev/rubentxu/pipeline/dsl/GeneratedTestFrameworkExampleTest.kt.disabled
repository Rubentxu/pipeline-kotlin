package dev.rubentxu.pipeline.dsl

import dev.rubentxu.pipeline.annotations.TestConfiguration
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Test de prueba de concepto para verificar que el framework de testing generado funciona.
 * 
 * Este test demuestra el uso del StepsBlockTestFramework que se genera automáticamente
 * por el procesador KSP cuando encuentra la clase StepsBlock anotada con @PipelineDsl.
 */
class GeneratedTestFrameworkExampleTest {

    private val testFramework = StepsBlockTestFramework({
        // Configuración del framework de testing
        detailedLogging(true)
        captureArguments(true)
    })

    @Test
    fun `el framework generado debe permitir configurar mocks`() {
        // Configurar mocks para diferentes pasos
        testFramework.onSh { command, returnStdout ->
            println("Mock sh llamado con: command=$command, returnStdout=$returnStdout")
            "mocked output"
        }

        testFramework.onEcho { message ->
            println("Mock echo llamado con: message=$message")
        }

        testFramework.onReadFile { file ->
            println("Mock readFile llamado con: file=$file")
            "mocked file content"
        }

        // El framework debe poder configurar mocks sin errores
        true shouldBe true
    }

    @Test
    fun `el framework debe permitir verificaciones`() {
        // Configurar mocks
        testFramework.onStep { block ->
            println("Mock step configurado")
        }

        // Verificaciones deben estar disponibles
        testFramework.verify {
            // Por ahora solo verificamos que el scope de verificación esté disponible
            totalInvocations shouldBe 0
        }
        
        // También verificaciones específicas por paso
        testFramework.verifyStep() shouldBe false // No hemos ejecutado ningún step aún
        testFramework.verifySh() shouldBe false
        testFramework.verifyEcho() shouldBe false
    }

    @Test  
    fun `el framework debe incluir todos los pasos detectados`() {
        // Verificar que todos los métodos de mock están disponibles
        testFramework.onStep { }
        testFramework.onParallel { emptyList() }
        testFramework.onSh { _, _ -> "" }
        testFramework.onEcho { }
        testFramework.onCheckout { "" }
        testFramework.onReadFile { "" }
        testFramework.onFileExists { false }
        testFramework.onWriteFile { _, _ -> }

        // Si llegamos aquí, todos los métodos están disponibles
        true shouldBe true
    }
}