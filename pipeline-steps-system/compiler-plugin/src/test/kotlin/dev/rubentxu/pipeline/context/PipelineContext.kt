package dev.rubentxu.pipeline.context

/**
 * Minimal PipelineContext interface for compiler plugin testing
 */
interface PipelineContext {
    fun log(message: String)
}