package dev.rubentxu.pipeline.backend.cdi

import dev.rubentxu.pipeline.backend.factories.PipelineContextFactory
import dev.rubentxu.pipeline.model.IPipelineContext
import pipeline.kotlin.extensions.deserializeYamlFileToMap
import pipeline.kotlin.extensions.resolveValueExpressions
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

class CascManager() {
    fun resolveConfig(path: Path): Result<IPipelineContext> {
        return try {
            val yamlResult: Result<Map<String, Any>> = path.deserializeYamlFileToMap()
            if (yamlResult.isFailure) return Result.failure(yamlResult.exceptionOrNull()!!)
            val rawYaml = yamlResult.getOrThrow()
            val resolvedYaml: Map<String, Any> = rawYaml.resolveValueExpressions() as Map<String, Any>
            val config = runBlocking {
                PipelineContextFactory.create(resolvedYaml)

            }
            Result.success(config)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}