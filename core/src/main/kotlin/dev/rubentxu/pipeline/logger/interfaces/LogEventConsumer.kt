package dev.rubentxu.pipeline.logger.interfaces

import dev.rubentxu.pipeline.logger.model.LogEvent

/**
 * Defines the contract for a log event consumer in the hybrid logging system.
 * 
 * This interface enables a push-based architecture where the LoggerManager
 * actively delivers events to registered consumers, rather than consumers
 * pulling from a Flow. This approach provides:
 * 
 * - Better performance through direct method calls
 * - Reduced object allocations
 * - Simplified backpressure handling
 * - Easier consumer lifecycle management
 * 
 * Performance characteristics:
 * - Zero allocation event delivery
 * - Lock-free event distribution
 * - Batched processing support
 * - Automatic error isolation between consumers
 * 
 * Usage:
 * ```kotlin
 * class MyConsumer : LogEventConsumer {
 *     override fun onEvent(event: LogEvent) {
 *         // Process event immediately
 *         println("Received: ${event.message}")
 *     }
 * }
 * 
 * loggerManager.addConsumer(MyConsumer())
 * ```
 */
fun interface LogEventConsumer {
    
    /**
     * Processes a log event delivered by the LoggerManager.
     * 
     * This method is called synchronously by the LoggerManager's internal
     * distribution loop. Implementations should:
     * 
     * - Process events quickly to avoid blocking other consumers
     * - Handle exceptions gracefully (they will be caught by the manager)
     * - Not retain references to the event object (it may be recycled)
     * - Be thread-safe if the same consumer is registered multiple times
     * 
     * Performance considerations:
     * - Called from the LoggerManager's consumer thread
     * - Events may be mutable and recycled after this call
     * - Should complete in < 1ms for optimal throughput
     * - Long-running operations should use async processing
     * 
     * @param event The log event to process. May be mutable and recycled.
     */
    fun onEvent(event: LogEvent)
    
    /**
     * Optional callback when consumer is added to a LoggerManager.
     * 
     * This method allows consumers to perform initialization when
     * they are registered. The default implementation does nothing.
     * 
     * @param loggerManager The manager this consumer was added to
     */
    fun onAdded(loggerManager: ILoggerManager) {
        // Default implementation does nothing
    }
    
    /**
     * Optional callback when consumer is removed from a LoggerManager.
     * 
     * This method allows consumers to clean up resources when
     * they are unregistered. The default implementation does nothing.
     * 
     * @param loggerManager The manager this consumer was removed from
     */
    fun onRemoved(loggerManager: ILoggerManager) {
        // Default implementation does nothing
    }
    
    /**
     * Optional callback when an error occurs during event processing.
     * 
     * The LoggerManager will catch exceptions thrown by onEvent() and
     * call this method. Consumers can use this to implement custom
     * error handling, logging, or recovery strategies.
     * 
     * The default implementation prints to stderr to avoid logging loops.
     * 
     * @param event The event that caused the error
     * @param exception The exception that occurred
     */
    fun onError(event: LogEvent, exception: Throwable) {
        System.err.println("LogEventConsumer error processing event: ${exception.message}")
        exception.printStackTrace(System.err)
    }
}