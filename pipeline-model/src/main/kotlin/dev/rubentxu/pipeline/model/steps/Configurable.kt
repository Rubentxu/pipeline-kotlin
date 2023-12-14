package dev.rubentxu.pipeline.model.steps

/**
 * The `Configurable` interface defines a contract for classes that can be configured with a map of configuration values.
 *
 * Any class implementing this interface should provide an implementation for the `configure` method, which should handle
 * the application of the given configuration map.
 */
interface Configurable {

    /**
     * Applies the provided configuration to the current object.
     *
     * Implementations of this function should handle the provided configuration map and apply it in a way that
     * is appropriate for the specific class. The configuration map keys are the names of the configuration options,
     * and the values are the configuration values.
     *
     * @param configuration The configuration map to apply. The keys are configuration options, and the values are
     * configuration values.
     * @throws IllegalArgumentException If a provided configuration option does not apply to this class.
     */
    fun configure(configuration: Map<String, Any>)
}