package dev.rubentxu.pipeline.logger.model

import java.time.Instant

/**
 * Mutable log event designed for object pooling and reuse.
 * 
 * This class eliminates GC pressure by allowing event objects to be reused
 * through an object pool. All properties are mutable to enable efficient
 * recycling without allocation overhead.
 * 
 * Performance characteristics:
 * - Near-zero allocation when used with object pooling
 * - Mutable StringBuilder for message reuse
 * - Mutable map for context data reuse
 * - Fast reset() method for pool return
 * - Conversion to immutable LogEvent for compatibility
 * 
 * Usage pattern:
 * 1. Acquire from pool: pool.acquire()
 * 2. Mutate with new data: event.level = INFO, event.message.append(...)
 * 3. Send to queue: queue.offer(event)
 * 4. Consumer processes and returns to pool: pool.release(event)
 */
data class MutableLogEvent(
    /**
     * Event timestamp in milliseconds since epoch.
     * Set automatically by logger implementations.
     */
    var timestamp: Long = 0L,
    
    /**
     * Log level indicating severity/importance.
     */
    var level: LogLevel = LogLevel.DEBUG,
    
    /**
     * Name of the logger that generated this event.
     * Typically class name or component identifier.
     */
    var loggerName: String = "",
    
    /**
     * Reusable StringBuilder for log messages.
     * Provides efficient string manipulation without allocation.
     */
    var message: StringBuilder = StringBuilder(256),
    
    /**
     * Correlation ID for request/operation tracking.
     * Used to correlate related log events across components.
     */
    var correlationId: String? = null,
    
    /**
     * Mutable context data map for structured logging.
     * Contains key-value pairs with additional event metadata.
     */
    var contextData: MutableMap<String, String> = mutableMapOf(),
    
    /**
     * Exception/throwable associated with error events.
     * Null for non-error log events.
     */
    var exception: Throwable? = null,
    
    /**
     * Source indicating where the log event originated.
     */
    var source: LogSource = LogSource.LOGGER
) {
    
    /**
     * Resets all mutable fields to default values for object pool reuse.
     * 
     * This method prepares the object for reuse by clearing all data
     * while preserving allocated collections (StringBuilder, MutableMap)
     * to avoid repeated allocations.
     */
    fun reset() {
        timestamp = 0L
        level = LogLevel.DEBUG
        loggerName = ""
        message.clear() // Clear content but keep capacity
        correlationId = null
        contextData.clear() // Clear entries but keep backing array
        exception = null
        source = LogSource.LOGGER
    }
    
    /**
     * Converts this mutable event to an immutable LogEvent for compatibility.
     * 
     * This method creates an immutable snapshot of the current state,
     * which is safe to pass to consumers that expect immutable data.
     * The conversion copies data to ensure immutability.
     * 
     * @return Immutable LogEvent snapshot
     */
    fun toImmutable(): LogEvent {
        return LogEvent(
            timestamp = Instant.ofEpochMilli(timestamp),
            level = level,
            loggerName = loggerName,
            message = message.toString(), // Create immutable string
            correlationId = correlationId,
            contextData = contextData.toMap(), // Create immutable copy
            exception = exception,
            source = source
        )
    }
    
    /**
     * Populates this mutable event from an immutable LogEvent.
     * 
     * This method is useful for scenarios where you need to convert
     * from immutable to mutable format, such as when adapting legacy code.
     * 
     * @param immutableEvent Source event to copy from
     */
    fun populateFrom(immutableEvent: LogEvent) {
        timestamp = immutableEvent.timestamp.toEpochMilli()
        level = immutableEvent.level
        loggerName = immutableEvent.loggerName
        message.clear().append(immutableEvent.message)
        correlationId = immutableEvent.correlationId
        contextData.clear()
        contextData.putAll(immutableEvent.contextData)
        exception = immutableEvent.exception
        source = immutableEvent.source
    }
    
    /**
     * Sets the message content efficiently using StringBuilder operations.
     * 
     * @param messageText New message content
     * @return This instance for method chaining
     */
    fun setMessage(messageText: String): MutableLogEvent {
        message.clear().append(messageText)
        return this
    }
    
    /**
     * Appends to the current message content.
     * 
     * @param additional Text to append
     * @return This instance for method chaining
     */
    fun appendMessage(additional: String): MutableLogEvent {
        message.append(additional)
        return this
    }
    
    /**
     * Sets a single context data entry.
     * 
     * @param key Context key
     * @param value Context value
     * @return This instance for method chaining
     */
    fun setContextData(key: String, value: String): MutableLogEvent {
        contextData[key] = value
        return this
    }
    
    /**
     * Adds multiple context data entries efficiently.
     * 
     * @param data Map of context entries to add
     * @return This instance for method chaining
     */
    fun addContextData(data: Map<String, String>): MutableLogEvent {
        contextData.putAll(data)
        return this
    }
    
    /**
     * Sets all event fields from individual parameters.
     * 
     * This method provides a convenient way to populate all fields
     * in a single call, useful for logger implementations.
     */
    fun populate(
        timestamp: Long,
        level: LogLevel,
        loggerName: String,
        message: String,
        correlationId: String? = null,
        contextData: Map<String, String> = emptyMap(),
        exception: Throwable? = null,
        source: LogSource = LogSource.LOGGER
    ): MutableLogEvent {
        this.timestamp = timestamp
        this.level = level
        this.loggerName = loggerName
        this.message.clear().append(message)
        this.correlationId = correlationId
        this.contextData.clear()
        this.contextData.putAll(contextData)
        this.exception = exception
        this.source = source
        return this
    }
    
    /**
     * Returns current message content as string without allocation when possible.
     */
    fun getMessageString(): String = message.toString()
    
    /**
     * Returns current message length without string conversion.
     */
    fun getMessageLength(): Int = message.length
    
    /**
     * Checks if the event has any context data.
     */
    fun hasContextData(): Boolean = contextData.isNotEmpty()
    
    /**
     * Checks if the event has an associated exception.
     */
    fun hasException(): Boolean = exception != null
    
    /**
     * Creates a compact string representation for debugging.
     * 
     * This method provides a lightweight toString() that doesn't
     * trigger unnecessary string allocations in normal operation.
     */
    override fun toString(): String {
        return "MutableLogEvent(${level.name}:$loggerName:${message.length}chars)"
    }
    
    companion object {
        /**
         * Creates a new MutableLogEvent with optimal initial capacity.
         * 
         * @param expectedMessageLength Expected message length for StringBuilder sizing
         * @param expectedContextEntries Expected number of context entries for Map sizing
         * @return New MutableLogEvent with optimized allocations
         */
        fun createOptimized(
            expectedMessageLength: Int = 256,
            expectedContextEntries: Int = 4
        ): MutableLogEvent {
            return MutableLogEvent(
                message = StringBuilder(expectedMessageLength),
                contextData = HashMap(expectedContextEntries * 4 / 3 + 1) // Load factor optimization
            )
        }
        
        /**
         * Factory method for creating instances from immutable events.
         * 
         * @param immutableEvent Source event to copy from
         * @return New MutableLogEvent populated with data from source
         */
        fun fromImmutable(immutableEvent: LogEvent): MutableLogEvent {
            val mutable = createOptimized(
                expectedMessageLength = immutableEvent.message.length + 50,
                expectedContextEntries = immutableEvent.contextData.size
            )
            mutable.populateFrom(immutableEvent)
            return mutable
        }
    }
}