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
 * Core engine for testing Pipeline DSL scripts with mocked steps
 */
class PipelineTestEngine {
    private val stepMocks = mutableMapOf<String, StepMockHandler>()
    private val stepRecorder = StepInvocationRecorder()
    private val mockExecutionContext = MockPipelineExecutionContext()
    
    /**
     * Execute a .pipeline.kts script file with mocked steps
     */
    fun executePipelineScript(scriptPath: String): PipelineExecutionResult {
        val scriptContent = loadPipelineScript(scriptPath)
        val mockedStepsBlock = createMockedStepsBlock()
        
        return mockExecutionContext.executePipelineScript(scriptContent, mockedStepsBlock)
    }
    
    /**
     * Execute pipeline script content directly with mocked steps
     */
    fun executePipelineScriptContent(scriptContent: String): PipelineExecutionResult {
        val mockedStepsBlock = createMockedStepsBlock()
        
        return mockExecutionContext.executePipelineScript(scriptContent, mockedStepsBlock)
    }
    
    /**
     * Configure mock behavior for a specific step
     */
    fun mockStep(stepName: String, handler: StepMockHandler) {
        stepMocks[stepName] = handler
    }
    
    /**
     * Get the step recorder for verification
     */
    fun getStepRecorder(): StepInvocationRecorder = stepRecorder
    
    /**
     * Load pipeline script content from file
     */
    private fun loadPipelineScript(scriptPath: String): String {
        val file = File(scriptPath)
        require(file.exists()) { "Pipeline script not found: $scriptPath" }
        require(file.extension == "kts") { "Pipeline script must be a .kts file: $scriptPath" }
        
        return file.readText()
    }
    
    /**
     * Create a mocked steps block with configured mock handlers
     */
    private fun createMockedStepsBlock(): MockedStepsBlock {
        return MockedStepsBlock(stepMocks, stepRecorder)
    }
}

/**
 * Handler for defining mock behavior of steps
 */
typealias StepMockHandler = (args: Map<String, Any>) -> MockResult

/**
 * Result returned by mocked steps
 */
data class MockResult(
    val exitCode: Int = 0,
    val output: String = "",
    val error: String = "",
    val executionTimeMs: Long = 100L,
    val environmentChanges: Map<String, String> = emptyMap()
)

/**
 * Result of pipeline execution in test environment
 */
sealed class PipelineExecutionResult {
    data class Success(val executionTimeMs: Long) : PipelineExecutionResult()
    data class Failure(val error: Exception, val executionTimeMs: Long) : PipelineExecutionResult()
    
    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure
}