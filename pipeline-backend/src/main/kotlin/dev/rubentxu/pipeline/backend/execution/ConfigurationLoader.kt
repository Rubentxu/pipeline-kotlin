package dev.rubentxu.pipeline.backend.execution

import dev.rubentxu.pipeline.model.PipelineConfig
import java.nio.file.Path

/**
 * Interface for loading pipeline configuration.
 */
interface ConfigurationLoader {
    /**
     * Loads configuration from a file.
     *
     * @param configPath The path to the configuration file
     * @return Result containing the pipeline configuration or an error
     */
    fun load(configPath: Path): Result<PipelineConfig>
}