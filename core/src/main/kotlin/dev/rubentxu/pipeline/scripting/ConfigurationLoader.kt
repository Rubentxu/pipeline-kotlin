package dev.rubentxu.pipeline.scripting

/**
 * Generic interface for loading configuration data.
 * 
 * @param T The type of configuration to load
 */
interface ConfigurationLoader<T> {
    /**
     * Loads configuration from the specified path.
     * 
     * @param configPath The path to the configuration file
     * @return The loaded configuration
     * @throws IllegalArgumentException if the config file doesn't exist or is invalid
     */
    fun loadConfiguration(configPath: String): T
}