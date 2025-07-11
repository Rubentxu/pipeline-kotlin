package dev.rubentxu.pipeline.scripting

import dev.rubentxu.pipeline.model.PipelineConfig
import dev.rubentxu.pipeline.model.pipeline.PipelineResult
import java.nio.file.Path

class PipelineOrchestrator<T>(
    private val evaluator: ScriptEvaluator<T>,
    private val configLoader: ConfigurationLoader<PipelineConfig>,
    private val resolver: ExecutorResolver<T>
) {
    fun execute(scriptPath: Path, configPath: Path, context: ExecutionContext): Result<PipelineResult> {
        return try {
            val definitionResult = evaluator.evaluate(scriptPath)
            if (definitionResult.isFailure) {
                return Result.failure(definitionResult.exceptionOrNull()!!)
            }
            
            val configResult = configLoader.load(configPath)
            if (configResult.isFailure) {
                return Result.failure(configResult.exceptionOrNull()!!)
            }
            
            val definition = definitionResult.getOrThrow()
            val config = configResult.getOrThrow()
            val agentManager = resolver.resolve(context)
            
            agentManager.execute(definition, config, listOf(scriptPath, configPath))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}