package dev.rubentxu.pipeline.steps.testing

import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for mocking @Step functions in testing scenarios.
 * 
 * This registry allows tests to override the behavior of @Step functions
 * with custom mock implementations, enabling easy testing of pipeline logic
 * without executing actual commands.
 */
class StepMockRegistry {
    
    private val mocks = ConcurrentHashMap<String, suspend (Map<String, Any>) -> Any>()
    private val executionHistory = mutableListOf<StepExecution>()
    
    /**
     * Mock a step with a custom implementation
     * 
     * @param stepName Name of the step to mock
     * @param mock Lambda that provides the mock behavior
     */
    fun <T> mockStep(stepName: String, mock: suspend (Map<String, Any>) -> T) {
        mocks[stepName] = mock as suspend (Map<String, Any>) -> Any
    }
    
    /**
     * Mock a step with typed context for easier parameter access
     * 
     * @param stepName Name of the step to mock
     * @param mock Lambda with typed context for parameter access
     */
    fun <T> mockStepWithParams(stepName: String, mock: suspend StepMockContext.() -> T) {
        mocks[stepName] = { params -> 
            StepMockContext(params).mock() as Any
        }
    }
    
    /**
     * Execute a mock or return null if no mock exists
     * 
     * @param stepName Name of the step
     * @param params Parameters passed to the step
     * @return Mock result or null if no mock registered
     */
    suspend fun executeOrMock(stepName: String, params: Map<String, Any>): Any? {
        recordExecution(stepName, params)
        return mocks[stepName]?.invoke(params)
    }
    
    /**
     * Check if a mock exists for a step
     * 
     * @param stepName Name of the step
     * @return True if mock exists
     */
    fun hasMock(stepName: String): Boolean = mocks.containsKey(stepName)
    
    /**
     * Get all registered mock names
     * 
     * @return Set of mocked step names
     */
    fun getMockedSteps(): Set<String> = mocks.keys.toSet()
    
    /**
     * Get all mocks (for internal use)
     */
    internal fun getAllMocks(): Map<String, suspend (Map<String, Any>) -> Any> = mocks.toMap()
    
    /**
     * Clear all mocks
     */
    fun clear() {
        mocks.clear()
        executionHistory.clear()
    }
    
    /**
     * Clear mocks for specific step
     * 
     * @param stepName Name of the step to clear
     */
    fun clearMock(stepName: String) {
        mocks.remove(stepName)
    }
    
    /**
     * Record step execution for verification
     */
    private fun recordExecution(stepName: String, params: Map<String, Any>) {
        executionHistory.add(StepExecution(stepName, params, System.currentTimeMillis()))
    }
    
    /**
     * Get execution history for verification
     */
    fun getExecutionHistory(): List<StepExecution> = executionHistory.toList()
    
    /**
     * Get executions for a specific step
     */
    fun getExecutionsFor(stepName: String): List<StepExecution> {
        return executionHistory.filter { it.stepName == stepName }
    }
    
    /**
     * Verify that a step was called
     * 
     * @param stepName Name of the step
     * @return True if step was called at least once
     */
    fun wasStepCalled(stepName: String): Boolean {
        return executionHistory.any { it.stepName == stepName }
    }
    
    /**
     * Verify that a step was called a specific number of times
     * 
     * @param stepName Name of the step
     * @param times Expected number of calls
     * @return True if step was called exactly the specified number of times
     */
    fun wasStepCalledTimes(stepName: String, times: Int): Boolean {
        return getExecutionsFor(stepName).size == times
    }
    
    /**
     * Verify that a step was called with specific parameters
     * 
     * @param stepName Name of the step
     * @param params Expected parameters
     * @return True if step was called with the specified parameters
     */
    fun wasStepCalledWith(stepName: String, params: Map<String, Any>): Boolean {
        return executionHistory.any { it.stepName == stepName && it.params == params }
    }
}

/**
 * Context for typed parameter access in mocks
 */
class StepMockContext(internal val params: Map<String, Any>) {
    
    /**
     * Get a typed parameter value
     * 
     * @param name Parameter name
     * @return Typed parameter value
     * @throws IllegalArgumentException if parameter not found or wrong type
     */
    fun <T> param(name: String): T {
        val value = params[name] ?: throw IllegalArgumentException("Parameter '$name' not found")
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
    
    /**
     * Get an optional typed parameter value
     * 
     * @param name Parameter name
     * @param default Default value if parameter not found
     * @return Typed parameter value or default
     */
    fun <T> paramOrDefault(name: String, default: T): T {
        val value = params[name] ?: return default
        @Suppress("UNCHECKED_CAST")
        return try {
            value as T
        } catch (e: ClassCastException) {
            default
        }
    }
    
    /**
     * Get an optional typed parameter value (nullable)
     * 
     * @param name Parameter name
     * @return Typed parameter value or null
     */
    fun <T> paramOrNull(name: String): T? {
        val value = params[name] ?: return null
        @Suppress("UNCHECKED_CAST")
        return try {
            value as T
        } catch (e: ClassCastException) {
            null
        }
    }
    
    /**
     * Check if parameter exists
     * 
     * @param name Parameter name
     * @return True if parameter exists
     */
    fun hasParam(name: String): Boolean = params.containsKey(name)
    
    /**
     * Get all parameters
     * 
     * @return Map of all parameters
     */
    fun allParams(): Map<String, Any> = params.toMap()
}

/**
 * Record of a step execution
 */
data class StepExecution(
    val stepName: String,
    val params: Map<String, Any>,
    val timestamp: Long
)