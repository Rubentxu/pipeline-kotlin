package dev.rubentxu.pipeline.backend.cdi

import dev.rubentxu.pipeline.backend.factories.PipelineContextFactory
import dev.rubentxu.pipeline.model.IPipelineContext
import kotlinx.coroutines.coroutineScope
import pipeline.kotlin.extensions.deserializeYamlFileToMap
import pipeline.kotlin.extensions.resolveValueExpressions
import java.nio.file.Path

class CascManager() {
    suspend fun resolveConfig(path: Path): Result<IPipelineContext> {
        return try {
            coroutineScope {
                val rawYaml = getRawConfig(path).getOrThrow()
                val resolvedYaml: Map<String, Any> = rawYaml.resolveValueExpressions() as Map<String, Any>
                val config = PipelineContextFactory.create(resolvedYaml)
                Result.success(config)
            }

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getRawConfig(path: Path): Result<Map<String, Any>> {
        return try {
            val yamlResult: Result<Map<String, Any>> = path.deserializeYamlFileToMap()
            if (yamlResult.isFailure) return Result.failure(yamlResult.exceptionOrNull()!!)
            val rawYaml = yamlResult.getOrThrow()
            Result.success(rawYaml)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}