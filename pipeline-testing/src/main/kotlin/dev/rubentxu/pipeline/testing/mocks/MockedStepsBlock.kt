package dev.rubentxu.pipeline.testing.mocks

import dev.rubentxu.pipeline.logger.IPipelineLogger
import dev.rubentxu.pipeline.logger.PipelineLogger
import dev.rubentxu.pipeline.testing.MockResult
import dev.rubentxu.pipeline.testing.StepMockHandler
import dev.rubentxu.pipeline.model.scm.Scm
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Mocked implementation of StepsBlock for testing Pipeline DSL scripts
 * Replaces the real StepsBlock during test execution
 */
class MockedStepsBlock(
    private val stepMocks: Map<String, StepMockHandler>,
    private val recorder: StepInvocationRecorder
) {
    
    // Mock execution context properties
    private val logger: IPipelineLogger = PipelineLogger.getLogger()
    private var workingDirectory: String = System.getProperty("user.dir")
    private var env: MutableMap<String, String> = mutableMapOf()
    
    /**
     * Initialize the mock context (called from MockPipelineExecutionContext)
     */
    internal fun initializeContext(workingDir: String, environment: Map<String, String>) {
        workingDirectory = workingDir
        env = environment.toMutableMap()
    }
    
    /**
     * Get the mock result for a step without executing it
     */
    internal fun getMockResult(stepName: String, args: Map<String, Any>): MockResult {
        return stepMocks[stepName]?.invoke(args) ?: MockResult()
    }
    
    // Core Pipeline Steps - these are the main steps available in Pipeline DSL
    
    /**
     * Execute shell command step
     */
    fun sh(script: String, returnStdout: Boolean = false, returnStatus: Boolean = false): Any {
        val args = mapOf(
            "script" to script, 
            "returnStdout" to returnStdout, 
            "returnStatus" to returnStatus
        )
        val mockResult = stepMocks["sh"]?.invoke(args) ?: MockResult()
        
        recorder.recordInvocation("sh", args, mockResult)
        
        // Apply environment changes from mock result
        env.putAll(mockResult.environmentChanges)
        
        return when {
            returnStatus -> mockResult.exitCode
            returnStdout -> mockResult.output
            else -> Unit
        }
    }
    
    /**
     * Echo/print message step
     */
    fun echo(message: String) {
        val args = mapOf("message" to message)
        val mockResult = stepMocks["echo"]?.invoke(args) ?: MockResult()
        
        recorder.recordInvocation("echo", args, mockResult)
        
        // Simulate echo output in test environment
        logger.info("[MOCK ECHO] $message")
    }
    
    /**
     * Read file content step
     */
    fun readFile(file: String): String {
        val args = mapOf("file" to file)
        val mockResult = stepMocks["readFile"]?.invoke(args) ?: MockResult(output = "")
        
        recorder.recordInvocation("readFile", args, mockResult)
        return mockResult.output
    }
    
    /**
     * Write file content step
     */
    fun writeFile(file: String, text: String) {
        val args = mapOf("file" to file, "text" to text)
        val mockResult = stepMocks["writeFile"]?.invoke(args) ?: MockResult()
        
        recorder.recordInvocation("writeFile", args, mockResult)
    }
    
    /**
     * Check if file exists step
     */
    fun fileExists(file: String): Boolean {
        val args = mapOf("file" to file)
        val mockResult = stepMocks["fileExists"]?.invoke(args) ?: MockResult(output = "false")
        
        recorder.recordInvocation("fileExists", args, mockResult)
        return mockResult.output.toBoolean()
    }
    
    /**
     * Checkout SCM step
     */
    fun checkout(scm: Scm) {
        val args = mapOf("scm" to scm)
        val mockResult = stepMocks["checkout"]?.invoke(args) ?: MockResult()
        
        recorder.recordInvocation("checkout", args, mockResult)
    }
    
    /**
     * Change directory step (with closure execution)
     */
    fun <T> dir(path: String, block: () -> T): T {
        val currentPwd = env["PWD"]
        val currentDir = currentPwd ?: workingDirectory
        val newDir = Paths.get(currentDir).resolve(path).toString()
        
        // Mock the directory change
        env["PWD"] = newDir
        
        return try {
            val result = block()
            
            val args = mapOf("path" to path)
            val mockResult = stepMocks["dir"]?.invoke(args) ?: MockResult()
            recorder.recordInvocation("dir", args, mockResult)
            
            result
        } finally {
            // Restore previous directory
            if (currentPwd != null) {
                env["PWD"] = currentPwd
            } else {
                env.remove("PWD")
            }
        }
    }
    
    /**
     * Retry step execution
     */
    fun <T> retry(times: Int, block: () -> T): T {
        var retries = 0
        var lastException: Exception? = null
        
        while (retries < times) {
            try {
                val result = block()
                
                val args = mapOf("times" to times, "attempt" to retries + 1)
                val mockResult = stepMocks["retry"]?.invoke(args) ?: MockResult()
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
        val mockResult = stepMocks["retry"]?.invoke(args) ?: MockResult()
        recorder.recordInvocation("retry", args, mockResult)
        
        throw lastException ?: RuntimeException("Retry failed after $times attempts")
    }
    
    /**
     * Parallel execution step
     */
    fun <T> parallel(vararg branches: Pair<String, () -> T>): Map<String, T> = runBlocking {
        val results = branches.map { (name, block) ->
            async {
                logger.info("Starting parallel branch: $name")
                val result = block()
                logger.info("Finished parallel branch: $name")
                name to result
            }
        }.awaitAll().toMap()
        
        val args = mapOf("branches" to branches.map { it.first })
        val mockResult = stepMocks["parallel"]?.invoke(args) ?: MockResult()
        recorder.recordInvocation("parallel", args, mockResult)
        
        results
    }
    
    /**
     * Sleep/delay step
     */
    fun sleep(seconds: Long) {
        val args = mapOf("seconds" to seconds)
        val mockResult = stepMocks["sleep"]?.invoke(args) ?: MockResult()
        
        recorder.recordInvocation("sleep", args, mockResult)
        
        // In test environment, don't actually sleep, just simulate
        logger.info("[MOCK SLEEP] Sleeping for ${seconds}s")
    }
    
    /**
     * Error step - throws exception
     */
    fun error(message: String): Nothing {
        val args = mapOf("message" to message)
        val mockResult = stepMocks["error"]?.invoke(args) ?: MockResult()
        
        recorder.recordInvocation("error", args, mockResult)
        
        throw RuntimeException(message)
    }
    
    /**
     * WithCredentials step - execute block with credentials
     */
    fun <T> withCredentials(credentialsId: String, block: () -> T): T {
        // Mock credentials by setting environment variables
        val originalEnv = env.toMap()
        
        // Set mock credential environment variables
        env["MOCK_USERNAME"] = "test-user"
        env["MOCK_PASSWORD"] = "test-password"
        env["MOCK_TOKEN"] = "test-token"
        
        return try {
            val result = block()
            
            val args = mapOf("credentialsId" to credentialsId)
            val mockResult = stepMocks["withCredentials"]?.invoke(args) ?: MockResult()
            recorder.recordInvocation("withCredentials", args, mockResult)
            
            result
        } finally {
            // Restore original environment
            env.clear()
            env.putAll(originalEnv)
        }
    }
    
    /**
     * Generic step execution for custom steps
     */
    fun step(stepName: String, args: Map<String, Any> = emptyMap()): Any {
        val mockResult = stepMocks[stepName]?.invoke(args) ?: MockResult()
        recorder.recordInvocation(stepName, args, mockResult)
        return mockResult.output
    }
    
    /**
     * Get current environment variables (for Pipeline DSL access)
     */
    fun getEnv(): Map<String, String> = env.toMap()
    
    /**
     * Set environment variable
     */
    fun setEnv(name: String, value: String) {
        env[name] = value
    }
}