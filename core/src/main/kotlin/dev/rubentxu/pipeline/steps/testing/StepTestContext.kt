package dev.rubentxu.pipeline.steps.testing

import dev.rubentxu.pipeline.context.*
import dev.rubentxu.pipeline.dsl.PipelineBlock
import dev.rubentxu.pipeline.dsl.pipeline
import dev.rubentxu.pipeline.model.pipeline.*
import dev.rubentxu.pipeline.steps.EnvVars
import dev.rubentxu.pipeline.logger.PipelineLogger
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.KCallable

/**
 * DSL context for testing @Step functions with easy mocking capabilities.
 * 
 * This class provides a fluent DSL for:
 * - Setting up test environments
 * - Mocking @Step functions easily
 * - Creating test files and data
 * - Executing test pipelines
 * - Verifying step executions
 * 
 * Example usage:
 * ```kotlin
 * @Test
 * fun testDeployment() = runStepTest {
 *     mockStep(::sh) { params -> "mocked output" }
 *     mockStep(::dockerBuild) { params -> "mock-image-id" }
 *     
 *     testPipeline {
 *         stages {
 *             stage("Deploy") {
 *                 steps {
 *                     sh("kubectl apply -f deployment.yaml")
 *                     dockerBuild("myapp:latest")
 *                 }
 *             }
 *         }
 *     }
 *     
 *     verifyStepCalled(::sh)
 *     verifyStepCalled(::dockerBuild)
 * }
 * ```
 */
class StepTestContext(
    private val configuration: TestConfiguration
) {

    private val mockRegistry = StepMockRegistry()
    private val callHistory = mutableListOf<KCallable<*>>()

    constructor(
        workingDir: Path = Files.createTempDirectory("step-test"),
        environment: Map<String, String> = mapOf("TEST_MODE" to "true"),
        securityLevel: SecurityLevel = SecurityLevel.TRUSTED
    ) : this(TestConfiguration(workingDir, environment, securityLevel))

    data class TestConfiguration(
        val workingDir: Path,
        val environment: Map<String, String>,
        val securityLevel: SecurityLevel
    )
    
    private val createdFiles = mutableListOf<Path>()
    
    // ================================ 
    // Test Environment Setup
    // ================================
    
    /**
     * Create a test file with content
     * 
     * @param relativePath Relative path from working directory
     * @param content File content
     * @return Absolute path to created file
     */
    fun createTestFile(relativePath: String, content: String): Path {
        val filePath = configuration.workingDir.resolve(relativePath)
        
        // Create parent directories if needed
        filePath.parent?.let { parent ->
            if (!Files.exists(parent)) {
                Files.createDirectories(parent)
            }
        }
        
        Files.write(filePath, content.toByteArray())
        createdFiles.add(filePath)
        return filePath
    }
    
    /**
     * Create an empty test file
     * 
     * @param relativePath Relative path from working directory
     * @return Absolute path to created file
     */
    fun createTestFile(relativePath: String): Path {
        return createTestFile(relativePath, "")
    }
    
    /**
     * Create test directory
     * 
     * @param relativePath Relative path from working directory
     * @return Absolute path to created directory
     */
    fun createTestDirectory(relativePath: String): Path {
        val dirPath = configuration.workingDir.resolve(relativePath)
        Files.createDirectories(dirPath)
        return dirPath
    }
    
    /**
     * Set environment variable for test
     * 
     * @param key Environment variable key
     * @param value Environment variable value
     */
    fun setEnv(key: String, value: String) {
        (configuration.environment as MutableMap)[key] = value
    }
    
    /**
     * Get working directory
     * 
     * @return Test working directory
     */
    fun getWorkingDir(): Path = configuration.workingDir
    
    // ================================ 
    // Mocking API
    // ================================
    
    /**
     * Mock a step with a lambda function using a type-safe function reference.
     *
     * @param stepFunction The KCallable representing the @Step function (e.g., `::myStep`).
     * @param mock Lambda that provides the mock behavior.
     */
    fun <T> mockStep(stepFunction: KCallable<*>, mock: suspend (Map<String, Any>) -> T) {
        mockRegistry.mockStep(stepFunction.name, mock)
    }

    /**
     * Mock a step to return a specific value.
     *
     * @param stepFunction The KCallable representing the @Step function.
     * @param value The value to return when the step is called.
     */
    fun <T> mockStep(stepFunction: KCallable<*>, value: T) {
        mockRegistry.mockStep(stepFunction.name) { value }
    }

    // ================================ 
    // Pipeline Execution
    // ================================

    /**
     * Executes a pipeline block within the test context.
     * The pipeline will run with the configured mocks and environment.
     *
     * @param block The pipeline definition to execute.
     */
    fun testPipeline(block: suspend PipelineBlock.() -> Unit) = runBlocking {
        val pipeline = pipeline(block)

        val testStepExecutor = TestStepExecutor(mockRegistry, callHistory)

        val context = PipelineContext(
            pipelineConfig = pipeline.toConfig(),
            logger = PipelineLogger.defaultLogger,
            envVars = EnvVars(configuration.environment),
            workingDir = configuration.workingDir,
            securityManager = PipelineSecurityManager(configuration.securityLevel),
            stepExecutor = testStepExecutor
        )

        pipeline.execute(context)
    }

    // ================================ 
    // Verification API
    // ================================

    /**
     * Verify that a specific @Step function was called during the test execution.
     *
     * @param stepFunction The KCallable of the step to verify.
     * @param times The expected number of times the step was called. Defaults to 1.
     */
    fun verifyStepCalled(stepFunction: KCallable<*>, times: Int = 1) {
        val count = callHistory.count { it.name == stepFunction.name }
        assert(count == times) { "Expected step '${stepFunction.name}' to be called $times time(s), but was called $count time(s)." }
    }

    /**
     * Verify that a specific @Step function was not called.
     *
     * @param stepFunction The KCallable of the step to verify.
     */
    fun verifyStepNotCalled(stepFunction: KCallable<*>) {
        val count = callHistory.count { it.name == stepFunction.name }
        assert(count == 0) { "Expected step '${stepFunction.name}' not to be called, but was called $count time(s)." }
    }

    /**
     * Cleans up resources created during the test, such as temporary files.
     */
    fun cleanup() {
        createdFiles.forEach { path ->
            try {
                if (Files.isDirectory(path)) {
                    path.toFile().deleteRecursively()
                } else {
                    Files.deleteIfExists(path)
                }
            } catch (e: Exception) {
                System.err.println("Failed to delete test file: $path")
            }
        }
    }
}

/**
 * Utility function to run a step test with automatic cleanup.
 *
 * @param securityLevel Security level for the test context.
 * @param block Test block with StepTestContext.
 */
fun runStepTest(
    securityLevel: SecurityLevel = SecurityLevel.TRUSTED,
    block: suspend StepTestContext.() -> Unit
) {
    val context = StepTestContext(securityLevel = securityLevel)
    try {
        runBlocking { context.block() }
    } finally {
        context.cleanup()
    }
}

/**
 * Utility function to run a step test with custom configuration.
 *
 * @param workingDir Custom working directory.
 * @param environment Custom environment variables.
 * @param securityLevel Security level for the test context.
 * @param block Test block with StepTestContext.
 */
fun runStepTest(
    workingDir: Path,
    environment: Map<String, String> = mapOf("TEST_MODE" to "true"),
    securityLevel: SecurityLevel = SecurityLevel.TRUSTED,
    block: suspend StepTestContext.() -> Unit
) {
    val config = StepTestContext.TestConfiguration(workingDir, environment, securityLevel)
    val context = StepTestContext(config)
    try {
        runBlocking { context.block() }
    } finally {
        context.cleanup()
    }
}