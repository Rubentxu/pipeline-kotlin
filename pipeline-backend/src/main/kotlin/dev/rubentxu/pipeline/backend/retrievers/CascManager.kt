package dev.rubentxu.pipeline.backend.retrievers

import dev.rubentxu.pipeline.backend.factories.PipelineContextFactory
import dev.rubentxu.pipeline.backend.mapper.PropertySet
import dev.rubentxu.pipeline.backend.mapper.propertyPath
import dev.rubentxu.pipeline.backend.mapper.resolveValueExpressions
import dev.rubentxu.pipeline.model.IPipelineContext
import org.yaml.snakeyaml.Yaml
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path


fun Path.deserializeYamlFileToMap(): Result<PropertySet> {
    return try {
        val content = String(Files.readAllBytes(this), StandardCharsets.UTF_8)
        val yaml = Yaml().load<Map<String, Any?>>(content)
        val propertySet = PropertySet(data= yaml, absolutePath = "".propertyPath() )
        return Result.success(propertySet)
    } catch (e: Exception) {
        return Result.failure(e)
    }
}

class CascManager {
    suspend fun resolvePipelineContext(path: Path): Result<IPipelineContext> =
        getRawConfig(path)
            .map { it.resolveValueExpressions() }
            .mapCatching { PipelineContextFactory.create(it).getOrThrow() }
    fun getRawConfig(path: Path): Result<PropertySet> = path.deserializeYamlFileToMap()

}