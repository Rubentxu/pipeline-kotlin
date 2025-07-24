package dev.rubentxu.pipeline.logger

import dev.rubentxu.pipeline.logger.interfaces.ILogger
import dev.rubentxu.pipeline.logger.interfaces.ILoggerManager

/**
 * Global access point for Pipeline logging functionality.
 * Provides convenient static methods for logger creation and management.
 */
object PipelineLogger {
    
    private val defaultManager: ILoggerManager by lazy {
        DefaultLoggerManager()
    }
    
    /**
     * Gets a logger with the specified name using the default logger manager.
     * This method can be called from any context (suspend or non-suspend).
     * 
     * @param name The logger name, typically class or component name
     * @return A high-performance logger instance
     */
    fun getLogger(name: String = "Pipeline"): ILogger {
        return defaultManager.getLogger(name)
    }
    
    /**
     * Gets a logger for a specific class using the class name.
     * 
     * @param clazz The class to create a logger for
     * @return A high-performance logger instance
     */
    fun getLogger(clazz: Class<*>): ILogger {
        return getLogger(clazz.simpleName)
    }
    
    /**
     * Gets a logger for a specific class using Kotlin class reference.
     * 
     * @param clazz The Kotlin class to create a logger for
     * @return A high-performance logger instance
     */
    fun getLogger(clazz: kotlin.reflect.KClass<*>): ILogger {
        return getLogger(clazz.simpleName ?: "Unknown")
    }
    
    /**
     * Gets the default logger manager instance.
     * 
     * @return The global logger manager
     */
    fun getManager(): ILoggerManager {
        return defaultManager
    }
    
    /**
     * Checks if the logging system is healthy.
     * 
     * @return true if the logging system is operational
     */
    fun isHealthy(): Boolean {
        return defaultManager.isHealthy()
    }
    
    /**
     * Gracefully shuts down the logging system.
     * Should be called during application shutdown.
     * 
     * @param timeoutMs Maximum time to wait for graceful shutdown
     */
    fun shutdown(timeoutMs: Long = 5000L) {
        defaultManager.shutdown(timeoutMs)
    }
}