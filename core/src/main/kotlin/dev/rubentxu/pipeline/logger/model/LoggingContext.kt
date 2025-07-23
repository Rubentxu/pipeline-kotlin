package dev.rubentxu.pipeline.logger.model

import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * A CoroutineContext element to hold logging-specific context like
 * correlation IDs and structured data. This is the coroutine-safe
 * replacement for ThreadLocal-based context management.
 */
data class LoggingContext(
    val correlationId: String? = null,
    val userId: String? = null,
    val sessionId: String? = null,
    val customData: Map<String, String> = emptyMap()
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<LoggingContext> {
        
        /**
         * Gets the current LoggingContext from the coroutine context.
         */
        suspend fun current(): LoggingContext? {
            return coroutineContext[LoggingContext]
        }
        
        /**
         * Executes a block with the specified LoggingContext.
         */
        suspend inline fun <T> withContext(
            context: LoggingContext,
            crossinline block: suspend () -> T
        ): T {
            return kotlinx.coroutines.withContext(context) {
                block()
            }
        }
    }
    
    override val key: CoroutineContext.Key<*> = Key
    
    /**
     * Combined context data including all fields as a map.
     */
    val contextData: Map<String, String>
        get() {
            val result = mutableMapOf<String, String>()
            userId?.let { result["userId"] = it }
            sessionId?.let { result["sessionId"] = it }
            result.putAll(customData)
            return result
        }
}