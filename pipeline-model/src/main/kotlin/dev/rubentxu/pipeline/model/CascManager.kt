package dev.rubentxu.pipeline.model

import pipeline.kotlin.extensions.deserializeYamlFileToMap
import pipeline.kotlin.extensions.resolveValueExpressions
import java.nio.file.Path

class CascManager {
    fun resolveConfig(path: Path): Result<PipelineContext> {
        try {
            val yamlResult: Result<Map<String, Any>> = path.deserializeYamlFileToMap()
            if (yamlResult.isFailure) return Result.failure(yamlResult.exceptionOrNull()!!)
            val rawYaml = yamlResult.getOrThrow()
            val resolvedYaml: Map<String, Any> = rawYaml.resolveValueExpressions() as Map<String, Any>
            val config = PipelineContext.create(resolvedYaml)
            return Result.success(config)
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }
}
