package dev.rubentxu.pipeline.testing.dsl

import dev.rubentxu.pipeline.testing.PipelineTestEngine
import dev.rubentxu.pipeline.testing.PipelineExecutionResult
import dev.rubentxu.pipeline.testing.MockResult
import dev.rubentxu.pipeline.testing.mocks.StepInvocationRecorder
import dev.rubentxu.pipeline.testing.mocks.wildcard
import io.kotest.assertions.fail
import io.kotest.matchers.shouldBe

/**
 * Builder DSL for creating Pipeline tests.
 *
 * Provides a fluent API for testing Pipeline DSL scripts.
 */
class PipelineTestBuilder {
    private val testEngine = PipelineTestEngine()
    private var pipelineScriptPath: String? = null
    private var pipelineScriptContent: String? = null
    
    /**
     * Sets the pipeline script to test (file path).
     *
     * @param scriptPath Path to the script file.
     */
    fun pipelineScript(scriptPath: String) {
        this.pipelineScriptPath = scriptPath
    }
    
    /**
     * Sets the pipeline script content directly.
     *
     * @param content Script content.
     */
    fun pipelineScriptContent(content: String) {
        this.pipelineScriptContent = content
    }
    
    /**
     * Mocks a pipeline step with a specific behavior.
     *
     * @param stepName Name of the step.
     * @param block Mock configuration.
     */
    fun mockStep(stepName: String, block: StepMockBuilder.() -> Unit) {
        val mockBuilder = StepMockBuilder()
        block(mockBuilder)
        testEngine.mockStep(stepName, mockBuilder.buildHandler())
    }
    
    /**
     * Executes the pipeline and verifies the steps.
     *
     * @param verificationBlock Verification block.
     * @return Execution result.
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
     * Executes the pipeline without verification (for basic execution tests).
     *
     * @return Execution result.
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
 * Builder to configure the mock behavior of a step.
 */
class StepMockBuilder {
    private var exitCode: Int = 0
    private var output: String = ""
    private var error: String = ""
    private var executionTimeMs: Long = 100L
    private var environmentChanges: Map<String, String> = emptyMap()
    private var customHandler: ((Map<String, Any>) -> MockResult)? = null
    
    /**
     * Sets the exit code returned by the mocked step.
     *
     * @param code Exit code.
     */
    fun returnExitCode(code: Int) {
        this.exitCode = code
    }
    
    /**
     * Sets the standard output returned by the mocked step.
     *
     * @param output Standard output.
     */
    fun returnOutput(output: String) {
        this.output = output
    }
    
    /**
     * Sets the error output returned by the mocked step.
     *
     * @param error Error output.
     */
    fun returnError(error: String) {
        this.error = error
    }
    
    /**
     * Sets the environment changes applied by the mocked step.
     *
     * @param changes Map of environment changes.
     */
    fun setEnvironmentChanges(changes: Map<String, String>) {
        this.environmentChanges = changes
    }
    
    /**
     * Simulates the execution time of the mocked step.
     *
     * @param timeMs Time in milliseconds.
     */
    fun simulateExecutionTime(timeMs: Long) {
        this.executionTimeMs = timeMs
    }
    
    /**
     * Defines a custom mock behavior using a lambda.
     *
     * @param handler Lambda returning a MockResult.
     */
    fun customBehavior(handler: (Map<String, Any>) -> MockResult) {
        this.customHandler = handler
    }
    
    /**
     * Builds the mock handler function.
     *
     * @return Mock handler.
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
 * DSL entry point for testing pipelines.
 *
 * @param testName Name of the test.
 * @param block Test configuration block.
 * @return Execution result.
 */
fun pipelineTest(testName: String, block: PipelineTestBuilder.() -> Unit): PipelineExecutionResult {
    val builder = PipelineTestBuilder()
    block(builder)
    return builder.execute()
}

/**
 * DSL entry point for testing pipelines with verification.
 *
 * @param testName Name of the test.
 * @param testBlock Test configuration block.
 * @param verificationBlock Verification block.
 * @return Execution result.
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