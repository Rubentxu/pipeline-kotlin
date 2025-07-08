package dev.rubentxu.pipeline.testing

import dev.rubentxu.pipeline.dsl.Step
import dev.rubentxu.pipeline.model.pipeline.Pipeline
import dev.rubentxu.pipeline.testing.mocks.MockedStepsBlock
import dev.rubentxu.pipeline.testing.mocks.StepInvocationRecorder
import dev.rubentxu.pipeline.testing.execution.MockPipelineExecutionContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Main engine for testing Pipeline DSL scripts with mocked steps.
 *
 * Allows executing pipeline scripts in a test environment, recording step invocations,
 * and defining mock behaviors for steps.
 */
class PipelineTestEngine {
    private val stepMocks = mutableMapOf<String, StepMockHandler>()
    private val stepRecorder = StepInvocationRecorder()
    private val mockExecutionContext = MockPipelineExecutionContext()
    
    /**
     * Executes a .pipeline.kts script file with mocked steps.
     *
     * @param scriptPath Path to the script file.
     * @return Result of the pipeline execution.
     */
    fun executePipelineScript(scriptPath: String): PipelineExecutionResult {
        val scriptContent = loadPipelineScript(scriptPath)
        val mockedStepsBlock = createMockedStepsBlock()
        
        return mockExecutionContext.executePipelineScript(scriptContent, mockedStepsBlock)
    }
    
    /**
     * Executes the content of a pipeline script directly with mocked steps.
     *
     * @param scriptContent Script content.
     * @return Result of the pipeline execution.
     */
    fun executePipelineScriptContent(scriptContent: String): PipelineExecutionResult {
        val mockedStepsBlock = createMockedStepsBlock()
        
        return mockExecutionContext.executePipelineScript(scriptContent, mockedStepsBlock)
    }
    
    /**
     * Sets the mock behavior for a specific step.
     *
     * @param stepName Name of the step.
     * @param handler Function defining the mock behavior.
     */
    fun mockStep(stepName: String, handler: StepMockHandler) {
        stepMocks[stepName] = handler
    }
    
    /**
     * Gets the step recorder for verification.
     *
     * @return Instance of StepInvocationRecorder.
     */
    fun getStepRecorder(): StepInvocationRecorder = stepRecorder
    
    /**
     * Loads the content of a pipeline script from a file.
     *
     * @param scriptPath Path to the file.
     * @return File content.
     */
    private fun loadPipelineScript(scriptPath: String): String {
        val file = File(scriptPath)
        require(file.exists()) { "Pipeline script not found: $scriptPath" }
        require(file.extension == "kts") { "Pipeline script must be a .kts file: $scriptPath" }
        
        return file.readText()
    }
    
    /**
     * Creates a mocked steps block with the configured handlers.
     *
     * @return Instance of MockedStepsBlock.
     */
    private fun createMockedStepsBlock(): MockedStepsBlock {
        return MockedStepsBlock(stepMocks, stepRecorder)
    }
}

/**
 * Handler to define the mock behavior of steps.
 *
 * @param args Step arguments.
 * @return Mock result of the step.
 */
typealias StepMockHandler = (args: Map<String, Any>) -> MockResult

/**
 * Result returned by mocked steps.
 *
 * @property exitCode Simulated exit code.
 * @property output Simulated standard output.
 * @property error Simulated error output.
 * @property executionTimeMs Simulated execution time in milliseconds.
 * @property environmentChanges Simulated environment changes.
 */
data class MockResult(
    val exitCode: Int = 0,
    val output: String = "",
    val error: String = "",
    val executionTimeMs: Long = 100L,
    val environmentChanges: Map<String, String> = emptyMap()
)

/**
 * Result of pipeline execution in test environment.
 */
sealed class PipelineExecutionResult {
    /**
     * Successful execution.
     *
     * @property executionTimeMs Execution time in milliseconds.
     */
    data class Success(val executionTimeMs: Long) : PipelineExecutionResult()
    /**
     * Failed execution.
     *
     * @property error Exception thrown.
     * @property executionTimeMs Execution time in milliseconds.
     */
    data class Failure(val error: Exception, val executionTimeMs: Long) : PipelineExecutionResult()
    
    /**
     * Indicates if the execution was successful.
     */
    val isSuccess: Boolean get() = this is Success
    /**
     * Indicates if the execution failed.
     */
    val isFailure: Boolean get() = this is Failure
}