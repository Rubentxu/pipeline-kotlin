package dev.rubentxu.pipeline.steps.testing

import dev.rubentxu.pipeline.dsl.StepsBlock
import dev.rubentxu.pipeline.model.pipeline.Pipeline

/**
 * Enhanced StepsBlock for testing scenarios that supports mocking of @Step functions.
 * 
 * This class extends StepsBlock to provide mocking capabilities, allowing tests
 * to override the behavior of @Step functions with custom mock implementations.
 * 
 * Key features:
 * - Transparent mocking: Mocked steps are called instead of real implementations
 * - Execution recording: All step executions are recorded for verification
 * - Type-safe mocking: Supports typed parameter access in mocks
 * - Easy verification: Simple APIs to verify step calls and parameters
 */
class TestableStepsBlock(
    pipeline: Pipeline,
    private val mockRegistry: StepMockRegistry = StepMockRegistry()
) : StepsBlock(pipeline) {
    
    /**
     * Override executeStep to intercept and mock @Step function calls
     * 
     * This method:
     * 1. Checks if a mock exists for the step
     * 2. If mock exists, executes the mock instead of the real step
     * 3. If no mock, delegates to the parent implementation
     * 4. Records all executions for verification
     */
    override suspend fun executeStep(stepName: String, config: Map<String, Any>): Any {
        return if (mockRegistry.hasMock(stepName)) {
            // Execute mock instead of real step
            val result = mockRegistry.executeOrMock(stepName, config)
            result ?: Unit // Return Unit if mock returns null
        } else {
            // Delegate to parent for real execution
            super.executeStep(stepName, config)
        }
    }
    
    /**
     * Mock a step with a lambda function
     * 
     * @param stepName Name of the step to mock
     * @param mock Lambda that provides the mock behavior
     */
    fun <T> mockStep(stepName: String, mock: suspend (Map<String, Any>) -> T) {
        mockRegistry.mockStep(stepName, mock)
    }
    
    /**
     * Mock a step with typed parameter context
     * 
     * @param stepName Name of the step to mock
     * @param mock Lambda with typed context for easier parameter access
     */
    fun <T> mockStepWithParams(stepName: String, mock: suspend StepMockContext.() -> T) {
        mockRegistry.mockStepWithParams(stepName, mock)
    }
    
    /**
     * Clear all mocks
     */
    fun clearAllMocks() {
        mockRegistry.clear()
    }
    
    /**
     * Clear mock for specific step
     * 
     * @param stepName Name of the step to clear
     */
    fun clearMock(stepName: String) {
        mockRegistry.clearMock(stepName)
    }
    
    /**
     * Check if a step is mocked
     * 
     * @param stepName Name of the step
     * @return True if step is mocked
     */
    fun isStepMocked(stepName: String): Boolean {
        return mockRegistry.hasMock(stepName)
    }
    
    /**
     * Get all mocked step names
     * 
     * @return Set of mocked step names
     */
    fun getMockedSteps(): Set<String> {
        return mockRegistry.getMockedSteps()
    }
    
    // ================================
    // Verification API
    // ================================
    
    /**
     * Verify that a step was called
     * 
     * @param stepName Name of the step
     * @throws AssertionError if step was not called
     */
    fun verifyStepCalled(stepName: String) {
        if (!mockRegistry.wasStepCalled(stepName)) {
            throw AssertionError("Step '$stepName' was not called")
        }
    }
    
    /**
     * Verify that a step was called a specific number of times
     * 
     * @param stepName Name of the step
     * @param times Expected number of calls
     * @throws AssertionError if step was not called the expected number of times
     */
    fun verifyStepCalledTimes(stepName: String, times: Int) {
        if (!mockRegistry.wasStepCalledTimes(stepName, times)) {
            val actualTimes = mockRegistry.getExecutionsFor(stepName).size
            throw AssertionError("Step '$stepName' was called $actualTimes times, expected $times")
        }
    }
    
    /**
     * Verify that a step was called with specific parameters
     * 
     * @param stepName Name of the step
     * @param params Expected parameters
     * @throws AssertionError if step was not called with the expected parameters
     */
    fun verifyStepCalledWith(stepName: String, params: Map<String, Any>) {
        if (!mockRegistry.wasStepCalledWith(stepName, params)) {
            val executions = mockRegistry.getExecutionsFor(stepName)
            val actualParams = executions.map { it.params }
            throw AssertionError(
                "Step '$stepName' was not called with expected parameters $params. " +
                "Actual calls: $actualParams"
            )
        }
    }
    
    /**
     * Verify that a step was never called
     * 
     * @param stepName Name of the step
     * @throws AssertionError if step was called
     */
    fun verifyStepNotCalled(stepName: String) {
        if (mockRegistry.wasStepCalled(stepName)) {
            val executions = mockRegistry.getExecutionsFor(stepName)
            throw AssertionError("Step '$stepName' was called ${executions.size} times, expected 0")
        }
    }
    
    /**
     * Get execution history for a specific step
     * 
     * @param stepName Name of the step
     * @return List of executions for the step
     */
    fun getExecutionsFor(stepName: String): List<StepExecution> {
        return mockRegistry.getExecutionsFor(stepName)
    }
    
    /**
     * Get all execution history
     * 
     * @return List of all step executions
     */
    fun getAllExecutions(): List<StepExecution> {
        return mockRegistry.getExecutionHistory()
    }
    
    // ================================
    // Convenience Methods for Common Steps
    // ================================
    
    /**
     * Mock shell command execution
     * 
     * @param mock Lambda that provides mock behavior for shell commands
     */
    fun mockShell(mock: suspend (command: String, returnStdout: Boolean) -> String) {
        mockStepWithParams("sh") {
            val command = param<String>("command")
            val returnStdout = paramOrDefault("returnStdout", false)
            mock(command, returnStdout)
        }
    }
    
    /**
     * Mock echo step (typically returns Unit)
     * 
     * @param mock Lambda that provides mock behavior for echo
     */
    fun mockEcho(mock: suspend (message: String) -> Unit = {}) {
        mockStepWithParams("echo") {
            val message = param<String>("message")
            mock(message)
        }
    }
    
    /**
     * Mock file read operation
     * 
     * @param mock Lambda that provides mock file content
     */
    fun mockReadFile(mock: suspend (filePath: String) -> String) {
        mockStepWithParams("readFile") {
            val filePath = param<String>("file")
            mock(filePath)
        }
    }
    
    /**
     * Mock file write operation
     * 
     * @param mock Lambda that handles file write (typically returns Unit)
     */
    fun mockWriteFile(mock: suspend (filePath: String, content: String) -> Unit = { _, _ -> }) {
        mockStepWithParams("writeFile") {
            val filePath = param<String>("file")
            val content = param<String>("text")
            mock(filePath, content)
        }
    }
    
    /**
     * Mock file existence check
     * 
     * @param mock Lambda that returns whether file exists
     */
    fun mockFileExists(mock: suspend (filePath: String) -> Boolean) {
        mockStepWithParams("fileExists") {
            val filePath = param<String>("file")
            mock(filePath)
        }
    }
    
    /**
     * Mock Docker build operation
     * 
     * @param mock Lambda that provides mock Docker build behavior
     */
    fun mockDockerBuild(mock: suspend (imageName: String, dockerfilePath: String) -> String) {
        mockStepWithParams("dockerBuild") {
            val imageName = param<String>("imageName")
            val dockerfilePath = paramOrDefault("dockerfilePath", "Dockerfile")
            mock(imageName, dockerfilePath)
        }
    }
    
    /**
     * Mock kubectl apply operation
     * 
     * @param mock Lambda that provides mock kubectl behavior
     */
    fun mockKubectlApply(mock: suspend (manifest: String, namespace: String) -> Unit = { _, _ -> }) {
        mockStepWithParams("kubectlApply") {
            val manifest = param<String>("manifest")
            val namespace = paramOrDefault("namespace", "default")
            mock(manifest, namespace)
        }
    }
}