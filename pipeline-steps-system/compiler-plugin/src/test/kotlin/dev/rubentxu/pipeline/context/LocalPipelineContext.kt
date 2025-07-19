package dev.rubentxu.pipeline.context

/**
 * Minimal LocalPipelineContext implementation for compiler plugin testing
 * This is a simplified version for test-only usage
 */
object LocalPipelineContext {
    val current: PipelineContext get() = TestPipelineContext
}

/**
 * Test implementation of PipelineContext
 */
object TestPipelineContext : PipelineContext {
    override fun log(message: String) {
        println("[TEST] $message")
    }
}