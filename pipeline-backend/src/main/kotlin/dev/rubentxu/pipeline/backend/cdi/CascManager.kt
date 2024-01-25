package dev.rubentxu.pipeline.backend.cdi

import arrow.core.raise.Raise
import com.charleskorn.kaml.Yaml
import dev.rubentxu.pipeline.backend.factories.PipelineContextFactory
import dev.rubentxu.pipeline.backend.mapper.PropertySet
import dev.rubentxu.pipeline.model.IPipelineContext
import dev.rubentxu.pipeline.model.PropertiesError
import kotlinx.serialization.decodeFromString
import dev.rubentxu.pipeline.backend.mapper.resolveValueExpressions
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path


context(Raise<PropertiesError>)
fun Path.deserializeYamlFileToMap(): PropertySet {
    return try {
        val content = String(Files.readAllBytes(this), StandardCharsets.UTF_8)
        val yaml = Yaml.default.decodeFromString<PropertySet>(content)
        return yaml
    } catch (e: Exception) {
        raise(PropertiesError("Error deserializing yaml file to map ${e.message}"))
    }
}

class CascManager {

    context(Raise<PropertiesError>)
    suspend fun resolveConfig(path: Path): IPipelineContext {
        val rawYaml = getRawConfig(path)
        val resolvedYaml = rawYaml.resolveValueExpressions()
        return PipelineContextFactory.create(resolvedYaml)

    }

    context(Raise<PropertiesError>)
    fun getRawConfig(path: Path): PropertySet = path.deserializeYamlFileToMap()

}