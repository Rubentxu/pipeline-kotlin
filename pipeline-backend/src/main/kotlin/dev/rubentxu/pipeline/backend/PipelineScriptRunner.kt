package dev.rubentxu.pipeline.backend

import dev.rubentxu.pipeline.backend.execution.ExecutionContext
import dev.rubentxu.pipeline.backend.execution.PipelineExecutionException
import dev.rubentxu.pipeline.backend.execution.PipelineOrchestrator
import dev.rubentxu.pipeline.logger.LogLevel
import dev.rubentxu.pipeline.logger.PipelineLogger
import dev.rubentxu.pipeline.logger.SocketLogConfigurationStrategy
import dev.rubentxu.pipeline.model.pipeline.PipelineResult
import dev.rubentxu.pipeline.model.pipeline.Status
import dev.rubentxu.pipeline.steps.EnvVars
import java.io.File
import java.nio.file.Path
import javax.script.ScriptException


class PipelineScriptRunner {

    companion object {
        @JvmStatic
        fun evalWithScriptEngineManager(
            scriptPath: String,
            configPath: String,
            jarLocation: File = File(PipelineScriptRunner::class.java.protectionDomain.codeSource.location.toURI()),
            logger: PipelineLogger = PipelineLogger(
                logLevel = LogLevel.TRACE,
                logConfigurationStrategy = SocketLogConfigurationStrategy()
            ),
        ): PipelineResult {
            // Note: jarLocation parameter is now ignored as it's no longer needed
            // This maintains backward compatibility while removing the unnecessary complexity
            
            val orchestrator = PipelineOrchestrator(logger = logger)
            val context = ExecutionContext(System.getenv())
            
            val result = orchestrator.execute(
                scriptPath = normalizeAndAbsolutePath(scriptPath),
                configPath = normalizeAndAbsolutePath(configPath),
                context = context
            )
            
            return result.getOrElse { exception ->
                when (exception) {
                    is PipelineExecutionException -> {
                        // Handle pipeline-specific errors with more context
                        logger.error("Pipeline execution failed: ${exception.message}")
                        exception.cause?.let { cause ->
                            if (cause is ScriptException) {
                                handleScriptExecutionException(cause, logger)
                            } else {
                                logger.error("Underlying cause: ${cause.message}")
                            }
                        }
                    }
                    is ScriptException -> {
                        handleScriptExecutionException(exception, logger)
                    }
                    else -> {
                        logger.error("Unexpected error: ${exception.message}")
                        if (logger.logLevel == LogLevel.TRACE) {
                            exception.printStackTrace()
                        }
                    }
                }
                PipelineResult(Status.FAILURE, emptyList(), EnvVars(mapOf()), mutableListOf())
            }
        }
    }
}

// Maneja las excepciones ocurridas durante la ejecución del script.
fun handleScriptExecutionException(exception: Exception, logger: PipelineLogger, showStackTrace: Boolean = true) {
    val regex = """ERROR (.*) \(.*:(\d+):(\d+)\)""".toRegex()
    val match = regex.find(exception.message ?: "")

    if (match != null) {
        val (error, line, space) = match.destructured
        logger.errorBanner(listOf("Error in Pipeline definition: $error", "Line: $line", "Space: $space"))
    } else {
        logger.error("Error in Pipeline definition: ${exception.message}")
    }

    // Imprime el stacktrace completo si el flag está activado.
    if (showStackTrace && logger.logLevel.ordinal <= dev.rubentxu.pipeline.logger.LogLevel.DEBUG.ordinal) {
        exception.printStackTrace()
    }
}

fun normalizeAndAbsolutePath(file: String): Path {
    return Path.of(file).toAbsolutePath().normalize()
}

fun normalizeAndAbsolutePath(path: Path): Path {
    return path.toAbsolutePath().normalize()
}

