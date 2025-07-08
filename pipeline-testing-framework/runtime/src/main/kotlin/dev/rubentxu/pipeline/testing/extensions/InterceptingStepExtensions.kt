package dev.rubentxu.pipeline.testing.extensions

import dev.rubentxu.pipeline.dsl.StepsBlock
import dev.rubentxu.pipeline.model.scm.Scm
import dev.rubentxu.pipeline.testing.MockResult
import dev.rubentxu.pipeline.testing.StepMockHandler
import dev.rubentxu.pipeline.testing.mocks.StepInvocationRecorder

/**
 * Intercepting extension functions for StepsBlock that override the real implementations during testing.
 * 
 * These functions take precedence over the real StepBlockExtensions during script execution
 * in test environment, allowing us to capture and mock step calls.
 */

// Registry to hold mock handlers and recorder for the current test execution
object InterceptorRegistry {
    private var mockHandlers: Map<String, StepMockHandler> = emptyMap()
    private var recorder: StepInvocationRecorder? = null
    
    fun configure(handlers: Map<String, StepMockHandler>, stepRecorder: StepInvocationRecorder) {
        mockHandlers = handlers
        recorder = stepRecorder
    }
    
    fun getMockHandler(stepName: String): StepMockHandler? = mockHandlers[stepName]
    fun getRecorder(): StepInvocationRecorder? = recorder
    
    fun clear() {
        mockHandlers = emptyMap()
        recorder = null
    }
}

/**
 * Intercepted sh step - executes shell commands with mocking support
 */
fun StepsBlock.sh(command: String, returnStdout: Boolean = false, returnStatus: Boolean = false): Any {
    println("[DEBUG] Intercepted sh() called with command: $command")
    val args = mapOf(
        "script" to command,
        "returnStdout" to returnStdout,
        "returnStatus" to returnStatus
    )
    
    val mockResult = InterceptorRegistry.getMockHandler("sh")?.invoke(args) ?: MockResult()
    InterceptorRegistry.getRecorder()?.recordInvocation("sh", args, mockResult)
    println("[DEBUG] Recorded sh invocation")
    
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
fun StepsBlock.echo(message: String) {
    println("[DEBUG] Intercepted echo() called with message: $message")
    val args = mapOf("message" to message)
    val mockResult = InterceptorRegistry.getMockHandler("echo")?.invoke(args) ?: MockResult()
    InterceptorRegistry.getRecorder()?.recordInvocation("echo", args, mockResult)
    println("[DEBUG] Recorded echo invocation")
    
    // In test environment, log the mock echo
    logger.info("[MOCK ECHO] $message")
}

/**
 * Intercepted readFile step - reads file content with mocking support
 */
fun StepsBlock.readFile(file: String): String {
    val args = mapOf("file" to file)
    val mockResult = InterceptorRegistry.getMockHandler("readFile")?.invoke(args) ?: MockResult(output = "")
    InterceptorRegistry.getRecorder()?.recordInvocation("readFile", args, mockResult)
    
    if (mockResult.exitCode != 0) {
        throw RuntimeException(mockResult.error.ifEmpty { "Failed to read file: $file" })
    }
    
    return mockResult.output
}

/**
 * Intercepted writeFile step - writes file content with mocking support
 */
fun StepsBlock.writeFile(file: String, text: String) {
    val args = mapOf("file" to file, "text" to text)
    val mockResult = InterceptorRegistry.getMockHandler("writeFile")?.invoke(args) ?: MockResult()
    InterceptorRegistry.getRecorder()?.recordInvocation("writeFile", args, mockResult)
    
    if (mockResult.exitCode != 0) {
        throw RuntimeException(mockResult.error.ifEmpty { "Failed to write file: $file" })
    }
}

/**
 * Intercepted fileExists step - checks file existence with mocking support
 */
fun StepsBlock.fileExists(file: String): Boolean {
    val args = mapOf("file" to file)
    val mockResult = InterceptorRegistry.getMockHandler("fileExists")?.invoke(args) ?: MockResult(output = "false")
    InterceptorRegistry.getRecorder()?.recordInvocation("fileExists", args, mockResult)
    
    return mockResult.output.toBoolean()
}

/**
 * Intercepted checkout step - checks out source code with mocking support
 */
fun StepsBlock.checkout(scm: Scm): String {
    val args = mapOf("scm" to scm)
    val mockResult = InterceptorRegistry.getMockHandler("checkout")?.invoke(args) ?: MockResult(output = "mock-commit-id")
    InterceptorRegistry.getRecorder()?.recordInvocation("checkout", args, mockResult)
    
    if (mockResult.exitCode != 0) {
        throw RuntimeException(mockResult.error.ifEmpty { "Checkout failed" })
    }
    
    return mockResult.output
}

/**
 * Intercepted error step - throws an exception with mocking support
 */
fun StepsBlock.error(message: String): Nothing {
    val args = mapOf("message" to message)
    val mockResult = InterceptorRegistry.getMockHandler("error")?.invoke(args) ?: MockResult()
    InterceptorRegistry.getRecorder()?.recordInvocation("error", args, mockResult)
    
    throw RuntimeException(message)
}

/**
 * Intercepted sleep step - adds delays with mocking support
 */
fun StepsBlock.sleep(seconds: Long) {
    val args = mapOf("seconds" to seconds)
    val mockResult = InterceptorRegistry.getMockHandler("sleep")?.invoke(args) ?: MockResult()
    InterceptorRegistry.getRecorder()?.recordInvocation("sleep", args, mockResult)
    
    // In test environment, don't actually sleep
    logger.info("[MOCK SLEEP] Sleeping for ${seconds}s")
}

/**
 * Intercepted retry step - retries operations with mocking support
 */
fun <T> StepsBlock.retry(times: Int, block: () -> T): T {
    var retries = 0
    var lastException: Exception? = null
    
    while (retries < times) {
        try {
            val result = block()
            
            val args = mapOf("times" to times, "attempt" to retries + 1)
            val mockResult = InterceptorRegistry.getMockHandler("retry")?.invoke(args) ?: MockResult()
            InterceptorRegistry.getRecorder()?.recordInvocation("retry", args, mockResult)
            
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
    val mockResult = InterceptorRegistry.getMockHandler("retry")?.invoke(args) ?: MockResult()
    InterceptorRegistry.getRecorder()?.recordInvocation("retry", args, mockResult)
    
    throw lastException ?: RuntimeException("Retry failed after $times attempts")
}

/**
 * Intercepted parallel step - executes steps in parallel with mocking support
 */
fun <T> StepsBlock.parallel(vararg branches: Pair<String, () -> T>): Map<String, T> {
    val args = mapOf("branches" to branches.map { it.first })
    val mockResult = InterceptorRegistry.getMockHandler("parallel")?.invoke(args) ?: MockResult()
    InterceptorRegistry.getRecorder()?.recordInvocation("parallel", args, mockResult)
    
    // Execute the branches (they might contain other intercepted calls)
    val results = branches.associate { (name, block) ->
        logger.info("Starting parallel branch: $name")
        val result = block()
        logger.info("Finished parallel branch: $name")
        name to result
    }
    
    return results
}

/**
 * Intercepted dir step - changes directory with mocking support
 */
fun <T> StepsBlock.dir(path: String, block: () -> T): T {
    val args = mapOf("path" to path)
    val mockResult = InterceptorRegistry.getMockHandler("dir")?.invoke(args) ?: MockResult()
    InterceptorRegistry.getRecorder()?.recordInvocation("dir", args, mockResult)
    
    // Execute the block (it might contain other intercepted calls)
    return block()
}

/**
 * Intercepted withCredentials step - executes with credentials with mocking support
 */
fun <T> StepsBlock.withCredentials(credentialsId: String, block: () -> T): T {
    val args = mapOf("credentialsId" to credentialsId)
    val mockResult = InterceptorRegistry.getMockHandler("withCredentials")?.invoke(args) ?: MockResult()
    InterceptorRegistry.getRecorder()?.recordInvocation("withCredentials", args, mockResult)
    
    // Execute the block (it might contain other intercepted calls)
    return block()
}

/**
 * Complex operation step - handles complex operations with configuration
 */
fun StepsBlock.complexOperation(operation: String): String {
    val args = mapOf(
        "operation" to operation,
        "inputFile" to "data.csv",
        "outputFile" to "processed-data.json"
    )
    val mockResult = InterceptorRegistry.getMockHandler("complexOperation")?.invoke(args) ?: MockResult(output = "processed")
    InterceptorRegistry.getRecorder()?.recordInvocation("complexOperation", args, mockResult)
    
    if (mockResult.exitCode != 0) {
        throw RuntimeException(mockResult.error.ifEmpty { "Complex operation failed: $operation" })
    }
    
    return mockResult.output
}