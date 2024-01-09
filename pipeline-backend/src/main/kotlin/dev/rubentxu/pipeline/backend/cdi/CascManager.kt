package dev.rubentxu.pipeline.backend.cdi

import com.charleskorn.kaml.Yaml
import dev.rubentxu.pipeline.backend.factories.PipelineContextFactory
import dev.rubentxu.pipeline.model.IPipelineContext
import kotlinx.coroutines.coroutineScope
import pipeline.kotlin.extensions.LookupException
import pipeline.kotlin.extensions.resolveValueExpressions
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path


fun Path.deserializeYamlFileToMap(): Result<Map<String, Any>> {
    return try {
        val content = String(Files.readAllBytes(this), StandardCharsets.UTF_8)
//        val yaml = Yaml().load(content) as Map<String, Any>
        Yaml.default.decodeFromString(Any, content)
        Result.success(yaml)
    } catch (e: Exception) {
        Result.failure(LookupException("Error in YAML lookup ${e.javaClass.simpleName} ${e.message}", e))
    }
}

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