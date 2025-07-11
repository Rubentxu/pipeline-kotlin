package dev.rubentxu.pipeline.backend.execution.impl

import dev.rubentxu.pipeline.backend.execution.ConfigurationLoader
import dev.rubentxu.pipeline.model.CascManager
import dev.rubentxu.pipeline.model.PipelineConfig
import java.nio.file.Path

/**
 * Default implementation of ConfigurationLoader using CascManager.
 */
class DefaultConfigurationLoader : ConfigurationLoader {
    
    private val cascManager = CascManager()
    
    override fun load(configPath: Path): Result<PipelineConfig> {
        return cascManager.resolveConfig(configPath.normalize())
    }
}