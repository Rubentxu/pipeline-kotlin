package dev.rubentxu.pipeline.testing.mocks

import dev.rubentxu.pipeline.dsl.StepsBlock
import dev.rubentxu.pipeline.model.pipeline.Pipeline
import dev.rubentxu.pipeline.model.scm.Scm
import dev.rubentxu.pipeline.testing.MockResult
import dev.rubentxu.pipeline.testing.StepMockHandler
import kotlinx.coroutines.runBlocking

/**
 * Intercepting implementation of StepsBlock that captures method calls instead of parsing strings.
 * 
 * This class replaces the string parsing approach in MockPipelineExecutionContext by directly
 * intercepting and mocking the step method calls. This provides a more reliable and maintainable
 * testing framework.
 * 
 * ## Key Features
 * - **Method Interception**: Directly intercepts step method calls instead of parsing strings
 * - **Type Safety**: Maintains exact method signatures and parameter types
 * - **Mock Behavior**: Supports custom mock behavior through StepMockHandler
 * - **Call Recording**: Records all method calls for verification in tests
 * - **Error Handling**: Supports failing steps based on mock configuration
 * 
 * ## Usage
 * ```kotlin
 * val mockHandlers = mapOf(
 *     "sh" to { args -> MockResult(output = "Success", exitCode = 0) }
 * )
 * val recorder = StepInvocationRecorder()
 * val interceptor = InterceptingStepsBlock(pipeline, mockHandlers, recorder)
 * 
 * // Direct method calls are intercepted and mocked
 * interceptor.sh("./gradlew build")
 * interceptor.echo("Build completed")
 * ```
 */
class InterceptingStepsBlock(
    pipeline: Pipeline,
    private val mockHandlers: Map<String, StepMockHandler>,
    private val recorder: StepInvocationRecorder
) : StepsBlock(pipeline) {

    /**
     * Intercepted sh step - executes shell commands with mocking support
     */
    fun sh(command: String, returnStdout: Boolean = false, returnStatus: Boolean = false): Any {
        val args = mapOf(
            "script" to command,
            "returnStdout" to returnStdout,
            "returnStatus" to returnStatus
        )
        
        val mockResult = mockHandlers["sh"]?.invoke(args) ?: MockResult()
        recorder.recordInvocation("sh", args, mockResult)
        
        // Check if this step should fail
        if (mockResult.exitCode != 0) {
            throw RuntimeException(mockResult.error.ifEmpty { "Command failed with exit code ${mockResult.exitCode}" })
        }
        
        return when {
            returnStatus -> mockResult.exitCode
            returnStdout -> mockResult.output
            else -> Unit
        }
    }

    /**
     * Intercepted echo step - prints messages with mocking support
     */
    fun echo(message: String) {
        val args = mapOf("message" to message)
        val mockResult = mockHandlers["echo"]?.invoke(args) ?: MockResult()
        recorder.recordInvocation("echo", args, mockResult)
        
        // In test environment, log the mock echo
        logger.info("[MOCK ECHO] $message")
    }

    /**
     * Intercepted readFile step - reads file content with mocking support
     */
    fun readFile(file: String): String {
        val args = mapOf("file" to file)
        val mockResult = mockHandlers["readFile"]?.invoke(args) ?: MockResult(output = "")
        recorder.recordInvocation("readFile", args, mockResult)
        
        if (mockResult.exitCode != 0) {
            throw RuntimeException(mockResult.error.ifEmpty { "Failed to read file: $file" })
        }
        
        return mockResult.output
    }

    /**
     * Intercepted writeFile step - writes file content with mocking support
     */
    fun writeFile(file: String, text: String) {
        val args = mapOf("file" to file, "text" to text)
        val mockResult = mockHandlers["writeFile"]?.invoke(args) ?: MockResult()
        recorder.recordInvocation("writeFile", args, mockResult)
        
        if (mockResult.exitCode != 0) {
            throw RuntimeException(mockResult.error.ifEmpty { "Failed to write file: $file" })
        }
    }

    /**
     * Intercepted fileExists step - checks file existence with mocking support
     */
    fun fileExists(file: String): Boolean {
        val args = mapOf("file" to file)
        val mockResult = mockHandlers["fileExists"]?.invoke(args) ?: MockResult(output = "false")
        recorder.recordInvocation("fileExists", args, mockResult)
        
        return mockResult.output.toBoolean()
    }

    /**
     * Intercepted checkout step - checks out source code with mocking support
     */
    fun checkout(scm: Scm): String {
        val args = mapOf("scm" to scm)
        val mockResult = mockHandlers["checkout"]?.invoke(args) ?: MockResult(output = "mock-commit-id")
        recorder.recordInvocation("checkout", args, mockResult)
        
        if (mockResult.exitCode != 0) {
            throw RuntimeException(mockResult.error.ifEmpty { "Checkout failed" })
        }
        
        return mockResult.output
    }

    /**
     * Intercepted error step - throws an exception with mocking support
     */
    fun error(message: String): Nothing {
        val args = mapOf("message" to message)
        val mockResult = mockHandlers["error"]?.invoke(args) ?: MockResult()
        recorder.recordInvocation("error", args, mockResult)
        
        throw RuntimeException(message)
    }

    /**
     * Intercepted sleep step - adds delays with mocking support
     */
    fun sleep(seconds: Long) {
        val args = mapOf("seconds" to seconds)
        val mockResult = mockHandlers["sleep"]?.invoke(args) ?: MockResult()
        recorder.recordInvocation("sleep", args, mockResult)
        
        // In test environment, don't actually sleep
        logger.info("[MOCK SLEEP] Sleeping for ${seconds}s")
    }

    /**
     * Intercepted retry step - retries operations with mocking support
     */
    fun <T> retry(times: Int, block: () -> T): T {
        var retries = 0
        var lastException: Exception? = null
        
        while (retries < times) {
            try {
                val result = block()
                
                val args = mapOf("times" to times, "attempt" to retries + 1)
                val mockResult = mockHandlers["retry"]?.invoke(args) ?: MockResult()
                recorder.recordInvocation("retry", args, mockResult)
                
                return result
            } catch (e: Exception) {
                lastException = e
                retries++
                if (retries >= times) {
                    break
                }
                
                // Add small delay to simulate retry behavior
                Thread.sleep(100)
            }
        }
        
        val args = mapOf("times" to times, "failed" to true)
        val mockResult = mockHandlers["retry"]?.invoke(args) ?: MockResult()
        recorder.recordInvocation("retry", args, mockResult)
        
        throw lastException ?: RuntimeException("Retry failed after $times attempts")
    }

    /**
     * Intercepted parallel step - executes steps in parallel with mocking support
     */
    fun <T> parallel(vararg branches: Pair<String, () -> T>): Map<String, T> = runBlocking {
        val args = mapOf("branches" to branches.map { it.first })
        val mockResult = mockHandlers["parallel"]?.invoke(args) ?: MockResult()
        recorder.recordInvocation("parallel", args, mockResult)
        
        // Execute the branches (they might contain other intercepted calls)
        val results = branches.associate { (name, block) ->
            logger.info("Starting parallel branch: $name")
            val result = block()
            logger.info("Finished parallel branch: $name")
            name to result
        }
        
        results
    }

    /**
     * Intercepted dir step - changes directory with mocking support
     */
    fun <T> dir(path: String, block: () -> T): T {
        val args = mapOf("path" to path)
        val mockResult = mockHandlers["dir"]?.invoke(args) ?: MockResult()
        recorder.recordInvocation("dir", args, mockResult)
        
        // Execute the block (it might contain other intercepted calls)
        return block()
    }

    /**
     * Intercepted withCredentials step - executes with credentials with mocking support
     */
    fun <T> withCredentials(credentialsId: String, block: () -> T): T {
        val args = mapOf("credentialsId" to credentialsId)
        val mockResult = mockHandlers["withCredentials"]?.invoke(args) ?: MockResult()
        recorder.recordInvocation("withCredentials", args, mockResult)
        
        // Execute the block (it might contain other intercepted calls)
        return block()
    }

    /**
     * Generic step execution for custom steps with named arguments
     */
    fun step(stepName: String, args: Map<String, Any> = emptyMap()): Any {
        val mockResult = mockHandlers[stepName]?.invoke(args) ?: MockResult()
        recorder.recordInvocation(stepName, args, mockResult)
        
        if (mockResult.exitCode != 0) {
            throw RuntimeException(mockResult.error.ifEmpty { "Step $stepName failed with exit code ${mockResult.exitCode}" })
        }
        
        return mockResult.output
    }

    /**
     * Generic step execution for custom steps with positional arguments
     */
    fun step(stepName: String, args: List<Any>): Any {
        // Convert list to map for mock handler
        val argsMap = args.mapIndexed { index, value -> "arg$index" to value }.toMap()
        val mockResult = mockHandlers[stepName]?.invoke(argsMap) ?: MockResult()
        // Record with the original list for proper verification
        recorder.recordInvocation(stepName, args, mockResult)
        
        if (mockResult.exitCode != 0) {
            throw RuntimeException(mockResult.error.ifEmpty { "Step $stepName failed with exit code ${mockResult.exitCode}" })
        }
        
        return mockResult.output
    }

    /**
     * Complex operation step - handles complex operations with configuration
     */
    fun complexOperation(operation: String): String {
        val args = mapOf(
            "operation" to operation,
            "inputFile" to "data.csv",
            "outputFile" to "processed-data.json"
        )
        val mockResult = mockHandlers["complexOperation"]?.invoke(args) ?: MockResult(output = "processed")
        recorder.recordInvocation("complexOperation", args, mockResult)
        
        if (mockResult.exitCode != 0) {
            throw RuntimeException(mockResult.error.ifEmpty { "Complex operation failed: $operation" })
        }
        
        return mockResult.output
    }
}