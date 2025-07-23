package dev.rubentxu.pipeline.context.managers.interfaces

/**
 * Parameter Manager Interface for pipeline parameter management.
 * Provides thread-safe parameter storage with reactive access through StateFlow.
 */
interface IParameterManager {

    /**
     * Set parameter value
     */
    fun set(key: String, value: Any)

    /**
     * Get parameter value with nullable return
     */
    fun <T> get(key: String): T?

    /**
     * Get parameter value with default
     */
    fun <T> get(key: String, defaultValue: T): T

    /**
     * Check if parameter exists
     */
    fun has(key: String): Boolean

    /**
     * Get all parameters as Map
     */
    fun getAll(): Map<String, Any>


}