package dev.rubentxu.pipeline.dsl

/**
 * Exception thrown when DSL engine operations fail.
 */
open class DslEngineException(message: String, cause: Throwable? = null) : Exception(message, cause)
