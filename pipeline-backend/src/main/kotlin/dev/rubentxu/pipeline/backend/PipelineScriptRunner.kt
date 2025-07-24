package dev.rubentxu.pipeline.backend

import dev.rubentxu.pipeline.dsl.DslManager
import dev.rubentxu.pipeline.dsl.DslEngineException
import java.io.File
import java.nio.file.Path

/**
 * PipelineScriptRunner refactorizado para usar la nueva arquitectura DSL.
 * 
 * CAMBIO ARQUITECTÓNICO PRINCIPAL:
 * - Antes: javax.script.ScriptEngineManager (Java-based)
 * - Ahora: DslManager con kotlin.script.experimental.api (100% Kotlin native)
 * 
 * SEPARACIÓN DE CONCERNS:
 * - DSL Compilation/Evaluation: -> DslManager.buildPipeline() 
 * - Pipeline Execution: -> JobExecutor.execute()
 */
class PipelineScriptRunner {

    private val dslManager = DslManager()

    /**
     * Ejecuta un script de pipeline usando el nuevo DslManager.
     * 
     * @param scriptPath Ruta al archivo script DSL
     * @param configPath Ruta al archivo de configuración (opcional por ahora)
     * @return Resultado de la ejecución del script
     */
    fun executeScript(scriptPath: String, configPath: String = ""): String {
        return try {
            val normalizedPath = normalizeAndAbsolutePath(scriptPath)
            val result = dslManager.executeScript(normalizedPath)
            "✅ Script ejecutado exitosamente: $result"
        } catch (e: DslEngineException) {
            "❌ Error DSL: ${e.message}"
        } catch (e: Exception) {
            "❌ Error inesperado: ${e.message}"
        }
    }

    /**
     * Ejecuta script desde contenido string (útil para tests)
     */
    fun executeScriptContent(scriptContent: String): String { 
        return try {
            val result = dslManager.executeScript(scriptContent)
            "✅ Script ejecutado exitosamente: $result"
        } catch (e: DslEngineException) {
            "❌ Error DSL: ${e.message}"
        } catch (e: Exception) {
            "❌ Error inesperado: ${e.message}"
        }
    }

    companion object {
        @JvmStatic
        fun evalWithDslManager(
            scriptPath: String,
            configPath: String
        ): String {
            val runner = PipelineScriptRunner()
            return runner.executeScript(scriptPath, configPath)
        }
    }
}

fun normalizeAndAbsolutePath(file: String): Path {
    return Path.of(file).toAbsolutePath().normalize()
}