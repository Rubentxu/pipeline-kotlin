package dev.rubentxu.pipeline.scripting

import dev.rubentxu.pipeline.model.CascManager
import dev.rubentxu.pipeline.model.PipelineConfig
import java.nio.file.Path

class PipelineConfigLoader : ConfigurationLoader<PipelineConfig> {
    
    override fun load(configPath: Path): Result<PipelineConfig> {
        return CascManager().resolveConfig(configPath.toAbsolutePath().normalize())
    }
}