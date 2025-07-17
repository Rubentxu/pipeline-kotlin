package dev.rubentxu.pipeline.context

/**
 * Minimal PipelineContext stub for compiler plugin testing.
 * This allows test compilation without circular dependencies.
 */
interface PipelineContext {
    fun log(message: String)
    fun getProperty(key: String): String?
}

/**
 * Stub implementation for testing
 */
class TestPipelineContext : PipelineContext {
    override fun log(message: String) {
        println("TestContext: $message")
    }
    
    override fun getProperty(key: String): String? {
        return "test-$key"
    }
}