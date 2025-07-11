package dev.rubentxu.pipeline.scripting

import dev.rubentxu.pipeline.backend.normalizeAndAbsolutePath
import dev.rubentxu.pipeline.model.CascManager
import dev.rubentxu.pipeline.model.PipelineConfig

/**
 * Configuration loader for Pipeline DSL that loads PipelineConfig objects.
 */
class PipelineConfigurationLoader : ConfigurationLoader<PipelineConfig> {
    
    override fun loadConfiguration(configPath: String): PipelineConfig {
        val configurationResult = CascManager().resolveConfig(normalizeAndAbsolutePath(configPath))
        
        if (configurationResult.isFailure) {
            throw IllegalArgumentException("Error reading config file: ${configurationResult.exceptionOrNull()?.message}")
        }
        
        return configurationResult.getOrThrow()
    }
}

/**
 * Configuration loader for Task DSL that loads simple configuration maps.
 */
class TaskConfigurationLoader : ConfigurationLoader<Map<String, Any>> {
    
    override fun loadConfiguration(configPath: String): Map<String, Any> {
        // For simplicity, the task DSL just loads a map from the existing pipeline config
        val configurationResult = CascManager().resolveConfig(normalizeAndAbsolutePath(configPath))
        
        if (configurationResult.isFailure) {
            throw IllegalArgumentException("Error reading config file: ${configurationResult.exceptionOrNull()?.message}")
        }
        
        // Convert PipelineConfig to a simple map for task DSL
        return mapOf(
            "environment" to (configurationResult.getOrThrow().environment ?: emptyMap())
        )
    }
}