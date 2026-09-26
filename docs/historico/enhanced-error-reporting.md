### Plan de Refactorización Detallado

El objetivo es desacoplar la evaluación del script del pipeline de su ejecución, introduciendo un sistema de motores DSL (`DslEngine`) gestionado por un `DslManager`.

---

#### **Paso 1: Modificar el `DslEngine` para devolver un `Pipeline`**

El motor del DSL debe centrarse en compilar y ejecutar el script para construir el objeto `Pipeline`, no en ejecutar el pipeline completo.

**Fichero:** `core/src/main/kotlin/dev/rubentxu/pipeline/dsl/engines/PipelineDslEngine.kt`

1.  **Cambiar el tipo de resultado genérico:** El `DslEngine` debe especializarse en devolver un `Pipeline`.
    ```kotlin
    // Antes
    class PipelineDslEngine(...) : DslEngine<PipelineResult>
    
    // Después
    class PipelineDslEngine(...) : DslEngine<Pipeline>
    ```

2.  **Actualizar el método `execute`:** Este método ahora compilará el script, lo ejecutará para obtener la `PipelineDefinition` y usará esta definición para construir y devolver el objeto `Pipeline`.
    *   Elimina la lógica de `executePipeline` de este método.
    *   El resultado de la ejecución del script será una `PipelineDefinition`.
    *   Construye el `Pipeline` usando `pipelineDefinition.build(pipelineConfig)`.

    ```kotlin
    // Reemplaza el método execute actual con esto
    override suspend fun execute(
        compiledScript: CompiledScript,
        context: DslExecutionContext
    ): DslExecutionResult<Pipeline> = withContext(Dispatchers.Default) {
        val startTime = Instant.now()
        try {
            logger.debug("Executing compiled pipeline script to build definition")
            val evaluationConfig = createEvaluationConfiguration(context)
            val executionResult = scriptEngine.execute(compiledScript, evaluationConfig)

            when (executionResult) {
                is ResultWithDiagnostics.Success -> {
                    // El resultado de la ejecución del script es la PipelineDefinition
                    val pipelineDefinition = executionResult.value.returnValue.scriptInstance as PipelineDefinition
                    
                    // Construir el Pipeline
                    val pipeline = pipelineDefinition.build(pipelineConfig)
                    
                    val executionTime = Instant.now().toEpochMilli() - startTime.toEpochMilli()
                    val metadata = DslExecutionMetadata(executionTimeMs = executionTime)
                    
                    DslExecutionResult.Success(pipeline, metadata)
                }
                is ResultWithDiagnostics.Failure -> {
                    val error = executionResult.reports.firstOrNull()?.let { convertToDslError(it) }
                        ?: DslError("EXECUTION_ERROR", "Script execution failed")
                    val metadata = DslExecutionMetadata(executionTimeMs = Instant.now().toEpochMilli() - startTime.toEpochMilli())
                    DslExecutionResult.Failure(error, metadata)
                }
            }
        } catch (e: Exception) {
            logger.error("Pipeline build failed: ${e.message}")
            val metadata = DslExecutionMetadata(executionTimeMs = Instant.now().toEpochMilli() - startTime.toEpochMilli())
            val error = DslError("EXECUTION_ERROR", e.message ?: "Unknown execution error", cause = e)
            DslExecutionResult.Failure(error, metadata)
        }
    }
    ```

3.  **Eliminar métodos innecesarios:** Los siguientes métodos ya no son responsabilidad de este motor.
    *   `extractPipelineFromResult(evaluationResult: EvaluationResult): Pipeline`
    *   `executePipeline(pipeline: Pipeline, context: DslExecutionContext): PipelineResult`

4.  **Actualizar la configuración de script:** Para que la ejecución del script devuelva la `PipelineDefinition`, modifica la configuración para que el resultado de la expresión final sea el valor de retorno.

    En `PipelineScriptConfiguration`:
    ```kotlin
    object PipelineScriptConfiguration : kotlin.script.experimental.api.ScriptCompilationConfiguration({
        // ...
        evaluation {
            resultMakeBody {
                val lastStatement = it.statements.lastOrNull()
                if (lastStatement is KtCallExpression) {
                    ResultValue.Value(
                        "definition",
                        (lastStatement as Any),
                        "dev.rubentxu.pipeline.model.pipeline.PipelineDefinition",
                        lastStatement
                    )
                } else {
                    ResultValue.Unit(lastStatement as Any)
                }
            }
        }
    })
    ```

---

#### **Paso 2: Simplificar la función `pipeline` en el DSL**

La función `pipeline` debe devolver directamente la `PipelineDefinition`, ya que el manejo de errores se delega al motor.

**Fichero:** `core/src/main/kotlin/dev/rubentxu/pipeline/dsl/Dsl.kt`

```kotlin
// Antes
fun pipeline(block: PipelineBlock.() -> Unit): Result<PipelineDefinition> {
    return try {
        val definition = PipelineDefinition(block)
        Result.success(definition)
    } catch (e: Exception) {
        Result.failure(e)
    }
}

// Después
fun pipeline(block: PipelineBlock.() -> Unit): PipelineDefinition {
    return PipelineDefinition(block)
}
```

---

#### **Paso 3: Crear e Implementar `DslManager`**

Este componente orquestará la selección y uso de los motores DSL.

**Fichero:** `core/src/main/kotlin/dev/rubentxu/pipeline/dsl/DslManager.kt`

1.  **Crear la clase `DslManager`:**
    ```kotlin
    package dev.rubentxu.pipeline.dsl

    import dev.rubentxu.pipeline.logger.PipelineLogger
    import dev.rubentxu.pipeline.model.pipeline.Pipeline
    import java.io.File

    class DslManager(private val registry: DslEngineRegistry) {

        private val logger = PipelineLogger.getLogger()

        suspend fun buildPipeline(scriptFile: File): Pipeline {
            val extension = scriptFile.extension
            val engine = registry.getEngineForExtension<Pipeline>(extension)
                ?: throw NoSuitableDslEngineException(extension = extension, message = "No DSL engine found for extension .$extension")

            logger.info("Using engine '${engine.engineName}' for script '${scriptFile.name}'")

            val compilationContext = engine.createDefaultCompilationContext()
            val executionContext = engine.createDefaultExecutionContext()

            val result = engine.compileAndExecute(scriptFile, compilationContext, executionContext)

            return when (result) {
                is DslExecutionResult.Success -> result.result
                is DslExecutionResult.Failure -> {
                    logger.error("Failed to build pipeline from script: ${result.error.message}")
                    throw DslEngineException("Pipeline script execution failed", result.error.cause)
                }
            }
        }
    }
    ```

---

#### **Paso 4: Refactorizar `PipelineScriptRunner` para usar `DslManager`**

El `PipelineScriptRunner` se convierte en el cliente principal del nuevo sistema DSL. Su responsabilidad es coordinar la obtención del `Pipeline` y luego pasarlo al `JobExecutor`.

**Fichero:** `pipeline-backend/src/main/kotlin/dev/rubentxu/pipeline/backend/PipelineScriptRunner.kt`

1.  **Modificar `evalWithScriptEngineManager`:**
    *   Instanciar el `DslManager` con un registro que contenga el `PipelineDslEngine`.
    *   Usar `dslManager.buildPipeline()` para obtener el objeto `Pipeline`.
    *   Eliminar las llamadas a `evaluateScriptFile` y `buildPipeline`.
    *   Pasar el `Pipeline` construido al `JobExecutor` o a la lógica de ejecución con agente.

    ```kotlin
    // Dentro de la clase PipelineScriptRunner
    companion object {
        @JvmStatic
        fun evalWithScriptEngineManager(
            scriptPath: String,
            configPath: String,
            // ... otros parámetros
        ): PipelineResult {
            // ... (lógica de logs y paths inicial)
            
            return try {
                // 1. Cargar configuración
                val configurationResult: Result<PipelineConfig> =
                    CascManager().resolveConfig(normalizeAndAbsolutePath(configPath))
                if (configurationResult.isFailure) {
                    // ... manejo de error de config
                }
                val configuration = configurationResult.getOrThrow()

                // 2. Inicializar el sistema DSL
                val pipelineDslEngine = PipelineDslEngine(configuration, logger)
                val registry = DefaultDslEngineRegistry(logger)
                registry.registerEngine(pipelineDslEngine)
                val dslManager = DslManager(registry)

                // 3. Construir el Pipeline usando el DslManager
                val scriptFile = normalizeAndAbsolutePath(scriptPath).toFile()
                val pipeline = runBlocking { dslManager.buildPipeline(scriptFile) }
                logger.system("Build Pipeline: $pipeline")

                // 4. Ejecutar el pipeline
                val listOfPaths = listOf(scriptPath, configPath).map { normalizeAndAbsolutePath(it) }
                executePipeline(pipeline, configuration, listOfPaths, logger)

            } catch (e: Exception) {
                handleScriptExecutionException(e, logger)
                PipelineResult(Status.FAILURE, emptyList(), EnvVars(mapOf()), mutableListOf())
            }
        }
    }
    ```

2.  **Eliminar funciones obsoletas:** Borra las siguientes funciones del fichero, ya que su lógica ahora está dentro del `PipelineDslEngine`.
    *   `getScriptEngine()`
    *   `evaluateScriptFile(scriptPath: String): PipelineDefinition`
    *   `buildPipeline(pipelineDef: PipelineDefinition, configuration: IPipelineConfig): Pipeline`

---

### Cómo Probar los Cambios

Para asegurar que la refactorización ha sido exitosa y no ha introducido regresiones, sigue estos pasos:

1.  **Pruebas Unitarias para `PipelineDslEngine`:**
    *   Crea un test que compile y ejecute un script `.pipeline.kts` de ejemplo.
    *   Verifica que el método `execute` devuelve un objeto `DslExecutionResult.Success` que contiene una instancia de `Pipeline`.
    *   Asegúrate de que las propiedades del `Pipeline` (agente, stages, etc.) coinciden con las definidas en el script.
    *   Prueba un script con errores de sintaxis y verifica que se devuelve un `DslCompilationResult.Failure`.
    *   Prueba un script que falle en tiempo de ejecución (ej. una variable no definida) y comprueba que se devuelve un `DslExecutionResult.Failure`.

2.  **Pruebas Unitarias para `DslManager`:**
    *   Crea un test que registre un `PipelineDslEngine` mockeado en el `DslEngineRegistry`.
    *   Llama a `dslManager.buildPipeline()` y verifica que se invoca el método `compileAndExecute` del motor correcto.
    *   Prueba a llamar con una extensión de fichero no soportada y confirma que se lanza una `NoSuitableDslEngineException`.

3.  **Pruebas de Integración para `PipelineScriptRunner`:**
    *   Esta es la prueba más importante. Reutiliza tus pruebas de extremo a extremo existentes que ejecutan un pipeline completo.
    *   Crea un fichero `test.pipeline.kts` simple.
    *   Llama a `PipelineScriptRunner.evalWithScriptEngineManager` con la ruta a tu script.
    *   Verifica que el pipeline se ejecuta correctamente y devuelve un `PipelineResult` con `Status.SUCCESS`.
    *   Comprueba que los logs generados durante la ejecución son los esperados.
    *   Prueba un pipeline que se ejecute en un agente Docker para asegurar que esa lógica sigue funcionando tras la refactorización.

4.  **Ejecución Manual:**
    *   Si tienes una forma de ejecutar tu aplicación desde la línea de comandos, úsala para correr un pipeline real. Esto validará que toda la configuración del classpath y las dependencias se resuelven correctamente en un entorno de ejecución real.