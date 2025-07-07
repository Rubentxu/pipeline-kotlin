package dev.rubentxu.pipeline.testing.dsl

import dev.rubentxu.pipeline.testing.PipelineTestEngine
import dev.rubentxu.pipeline.testing.PipelineExecutionResult
import dev.rubentxu.pipeline.testing.MockResult
import dev.rubentxu.pipeline.testing.mocks.StepInvocationRecorder
import dev.rubentxu.pipeline.testing.mocks.wildcard
import io.kotest.assertions.fail
import io.kotest.matchers.shouldBe

/**
 * DSL builder for creating Pipeline tests
 * Provides fluent API for testing Pipeline DSL scripts
 */
class PipelineTestBuilder {
    private val testEngine = PipelineTestEngine()
    private var pipelineScriptPath: String? = null
    private var pipelineScriptContent: String? = null
    
    /**
     * Set the pipeline script to test (file path)
     */
    fun pipelineScript(scriptPath: String) {
        this.pipelineScriptPath = scriptPath
    }
    
    /**
     * Set the pipeline script content directly
     */
    fun pipelineScriptContent(content: String) {
        this.pipelineScriptContent = content
    }
    
    /**
     * Mock a pipeline step with specific behavior
     */
    fun mockStep(stepName: String, block: StepMockBuilder.() -> Unit) {
        val mockBuilder = StepMockBuilder()
        block(mockBuilder)
        testEngine.mockStep(stepName, mockBuilder.buildHandler())
    }
    
    /**
     * Execute the pipeline and verify steps
     */
    fun executeAndVerify(verificationBlock: StepVerificationDsl.() -> Unit): PipelineExecutionResult {
        val result = if (pipelineScriptPath != null) {
            testEngine.executePipelineScript(pipelineScriptPath!!)
        } else if (pipelineScriptContent != null) {
            // Execute content directly without writing to file
            testEngine.executePipelineScriptContent(pipelineScriptContent!!)
        } else {
            fail("No pipeline script specified. Use pipelineScript() or pipelineScriptContent()")
        }
        
        // Perform verifications
        val verificationDsl = StepVerificationDsl(testEngine.getStepRecorder())
        verificationBlock(verificationDsl)
        
        return result
    }
    
    /**
     * Execute the pipeline without verification (for basic execution tests)
     */
    fun execute(): PipelineExecutionResult {
        return if (pipelineScriptPath != null) {
            testEngine.executePipelineScript(pipelineScriptPath!!)
        } else if (pipelineScriptContent != null) {
            // Execute content directly without writing to file
            testEngine.executePipelineScriptContent(pipelineScriptContent!!)
        } else {
            fail("No pipeline script specified. Use pipelineScript() or pipelineScriptContent()")
        }
    }
}

/**
 * Builder for configuring step mock behavior
 */
class StepMockBuilder {
    private var exitCode: Int = 0
    private var output: String = ""
    private var error: String = ""
    private var executionTimeMs: Long = 100L
    private var environmentChanges: Map<String, String> = emptyMap()
    private var customHandler: ((Map<String, Any>) -> MockResult)? = null
    
    /**
     * Set the exit code returned by the mocked step
     */
    fun returnExitCode(code: Int) {
        this.exitCode = code
    }
    
    /**
     * Set the output returned by the mocked step
     */
    fun returnOutput(output: String) {
        this.output = output
    }
    
    /**
     * Set the error output returned by the mocked step
     */
    fun returnError(error: String) {
        this.error = error
    }
    
    /**
     * Set environment changes applied by the mocked step
     */
    fun setEnvironmentChanges(changes: Map<String, String>) {
        this.environmentChanges = changes
    }
    
    /**
     * Set custom execution time simulation
     */
    fun simulateExecutionTime(timeMs: Long) {
        this.executionTimeMs = timeMs
    }
    
    /**
     * Define custom mock behavior with a lambda
     */
    fun customBehavior(handler: (Map<String, Any>) -> MockResult) {
        this.customHandler = handler
    }
    
    /**
     * Build the mock handler function
     */
    internal fun buildHandler(): (Map<String, Any>) -> MockResult {
        return customHandler ?: { _ ->
            MockResult(
                exitCode = exitCode,
                output = output,
                error = error,
                executionTimeMs = executionTimeMs,
                environmentChanges = environmentChanges
            )
        }
    }
}

/**
 * DSL entry point for pipeline testing
 */
fun pipelineTest(testName: String, block: PipelineTestBuilder.() -> Unit): PipelineExecutionResult {
    val builder = PipelineTestBuilder()
    block(builder)
    return builder.execute()
}

/**
 * DSL entry point for pipeline testing with verification
 */
fun pipelineTestWithVerification(
    testName: String, 
    testBlock: PipelineTestBuilder.() -> Unit,
    verificationBlock: StepVerificationDsl.() -> Unit
): PipelineExecutionResult {
    val builder = PipelineTestBuilder()
    testBlock(builder)
    return builder.executeAndVerify(verificationBlock)
}