package dev.rubentxu.pipeline.utils

import dev.rubentxu.pipeline.model.config.IPipelineConfig
import dev.rubentxu.pipeline.model.pipeline.Pipeline
import dev.rubentxu.pipeline.model.pipeline.PipelineDefinition
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/**
 * Utility function to normalize and convert a file path string to an absolute Path.
 */
fun normalizeAndAbsolutePath(file: String): Path {
    return Path.of(file).toAbsolutePath().normalize()
}

/**
 * Utility function to normalize and convert a Path to an absolute Path.
 */
fun normalizeAndAbsolutePath(path: Path): Path {
    return path.toAbsolutePath().normalize()
}

/**
 * Builds a pipeline using coroutines from a pipeline definition and configuration.
 */
fun buildPipeline(pipelineDef: PipelineDefinition, configuration: IPipelineConfig): Pipeline = runBlocking {
    pipelineDef.build(configuration)
}