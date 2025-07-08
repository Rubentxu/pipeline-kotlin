package dev.rubentxu.pipeline.testing.kotest

import dev.rubentxu.pipeline.testing.dsl.PipelineTestBuilder
import dev.rubentxu.pipeline.testing.dsl.StepVerificationDsl
import dev.rubentxu.pipeline.testing.PipelineExecutionResult
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.assertions.fail
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempFile

/**
 * Base Kotest specification for testing Pipeline DSL.
 *
 * Provides convenient DSL methods for testing Pipeline scripts.
 */
abstract class PipelineTestSpec : StringSpec() {
    
    /**
     * Tests a pipeline script with verification.
     *
     * @param testName Name of the test.
     * @param testBlock Test configuration block.
     * @param verificationBlock Verification block.
     */
    protected fun testPipeline(
        testName: String,
        testBlock: PipelineTestBuilder.() -> Unit,
        verificationBlock: StepVerificationDsl.() -> Unit
    ) {
        testName {
            val builder = PipelineTestBuilder()
            testBlock(builder)
            
            val result = builder.executeAndVerify(verificationBlock)
            result.isSuccess shouldBe true
        }
    }
    
    /**
     * Tests the execution of a pipeline script without verification.
     *
     * @param testName Name of the test.
     * @param testBlock Test configuration block.
     */
    protected fun testPipelineExecution(
        testName: String,
        testBlock: PipelineTestBuilder.() -> Unit
    ) {
        testName {
            val builder = PipelineTestBuilder()
            testBlock(builder)
            
            val result = builder.execute()
            result.isSuccess shouldBe true
        }
    }
    
    /**
     * Tests that a pipeline script fails with the expected error.
     *
     * @param testName Name of the test.
     * @param testBlock Test configuration block.
     * @param expectedErrorMatch Function to validate the error.
     */
    protected fun testPipelineFailure(
        testName: String,
        testBlock: PipelineTestBuilder.() -> Unit,
        expectedErrorMatch: (Exception) -> Boolean = { true }
    ) {
        testName {
            val builder = PipelineTestBuilder()
            testBlock(builder)
            
            val result = builder.execute()
            result.shouldBeInstanceOf<PipelineExecutionResult.Failure>()
            
            if (!expectedErrorMatch(result.error)) {
                fail("Error did not match expected pattern: ${result.error}")
            }
        }
    }
    
    /**
     * Creates a temporary pipeline script file with the given content.
     *
     * @param content Script content.
     * @return Path to the temporary file.
     */
    protected fun createTempPipelineScript(content: String): Path {
        val tempFile = createTempFile("test-pipeline", ".pipeline.kts")
        tempFile.toFile().writeText(content)
        return tempFile
    }
    
    /**
     * Helper to create simple pipeline script content.
     *
     * @param content Steps content.
     * @return Full script.
     */
    protected fun simplePipelineScript(content: String): String {
        return """
            pipeline {
                agent {
                    docker("openjdk:21")
                }
                
                stages {
                    stage("Test Stage") {
                        steps {
                            $content
                        }
                    }
                }
            }
        """.trimIndent()
    }
    
    /**
     * Helper to create a build pipeline script.
     *
     * @return Build script.
     */
    protected fun buildPipelineScript(): String {
        return simplePipelineScript("""
            sh("./gradlew clean")
            sh("./gradlew build")
            echo("Build completed successfully")
        """)
    }
    
    /**
     * Helper to create a pipeline script with file operations.
     *
     * @return Script with file operations.
     */
    protected fun fileOperationsPipelineScript(): String {
        return simplePipelineScript("""
            writeFile("build.gradle", "apply plugin: 'java'")
            if (fileExists("build.gradle")) {
                echo("Build file exists")
                val content = readFile("build.gradle")
                echo("Build file length: " + content.length)
            }
        """)
    }
    
    /**
     * Helper to create a pipeline script with parallel execution.
     *
     * @return Script with parallel steps.
     */
    protected fun parallelPipelineScript(): String {
        return simplePipelineScript("""
            parallel(
                "Unit Tests" to {
                    sh("./gradlew test")
                },
                "Integration Tests" to {
                    sh("./gradlew integrationTest")
                },
                "Static Analysis" to {
                    sh("./gradlew checkstyleMain")
                }
            )
        """)
    }
    
    /**
     * Helper to create a pipeline script with error handling.
     *
     * @return Script with error handling.
     */
    protected fun errorHandlingPipelineScript(): String {
        return simplePipelineScript("""
            retry(3) {
                sh("./gradlew build")
            }
            
            dir("subproject") {
                sh("./gradlew test")
            }
        """)
    }
}

/**
 * Extended specification for integration testing of Pipeline DSL.
 *
 * Provides additional utilities for complex testing scenarios.
 */
abstract class PipelineIntegrationTestSpec : PipelineTestSpec() {
    
    /**
     * Tests a pipeline with real file system operations.
     *
     * @param testName Name of the test.
     * @param setupBlock Setup block.
     * @param testBlock Test configuration block.
     * @param verificationBlock Verification block.
     */
    protected fun testPipelineWithFileSystem(
        testName: String,
        setupBlock: (Path) -> Unit,
        testBlock: PipelineTestBuilder.(Path) -> Unit,
        verificationBlock: StepVerificationDsl.(Path) -> Unit
    ) {
        testName {
            val tempDir = Files.createTempDirectory("pipeline-test")
            
            try {
                // Setup test environment
                setupBlock(tempDir)
                
                val builder = PipelineTestBuilder()
                testBlock(builder, tempDir)
                
                val result = builder.executeAndVerify { verificationBlock(tempDir) }
                result.isSuccess shouldBe true
            } finally {
                // Cleanup
                tempDir.toFile().deleteRecursively()
            }
        }
    }
    
    /**
     * Tests a pipeline script from resources.
     *
     * @param testName Name of the test.
     * @param resourcePath Path to the resource.
     * @param testBlock Test configuration block.
     * @param verificationBlock Verification block.
     */
    protected fun testPipelineFromResource(
        testName: String,
        resourcePath: String,
        testBlock: PipelineTestBuilder.() -> Unit,
        verificationBlock: StepVerificationDsl.() -> Unit
    ) {
        testName {
            val scriptContent = this::class.java.getResourceAsStream(resourcePath)?.use { inputStream ->
                inputStream.bufferedReader().readText()
            } ?: fail("Resource not found: $resourcePath")
            
            val builder = PipelineTestBuilder()
            builder.pipelineScriptContent(scriptContent)
            testBlock(builder)
            
            val result = builder.executeAndVerify(verificationBlock)
            result.isSuccess shouldBe true
        }
    }
    
    /**
     * Pipeline execution performance test.
     *
     * @param testName Name of the test.
     * @param maxExecutionTimeMs Maximum allowed time in ms.
     * @param testBlock Test configuration block.
     */
    protected fun testPipelinePerformance(
        testName: String,
        maxExecutionTimeMs: Long,
        testBlock: PipelineTestBuilder.() -> Unit
    ) {
        testName {
            val startTime = System.currentTimeMillis()
            
            val builder = PipelineTestBuilder()
            testBlock(builder)
            
            val result = builder.execute()
            val executionTime = System.currentTimeMillis() - startTime
            
            result.isSuccess shouldBe true
            if (executionTime > maxExecutionTimeMs) {
                fail("Pipeline execution took ${executionTime}ms, expected less than ${maxExecutionTimeMs}ms")
            }
        }
    }
}

/**
 * Kotest matcher extensions for Pipeline testing.
 */
object PipelineMatchers {
    
    /**
     * Matcher for successful pipeline execution.
     */
    fun beSuccessful() = object : io.kotest.matchers.Matcher<PipelineExecutionResult> {
        override fun test(value: PipelineExecutionResult): io.kotest.matchers.MatcherResult {
            return io.kotest.matchers.MatcherResult(
                value.isSuccess,
                { "Pipeline execution should be successful but was: $value" },
                { "Pipeline execution should not be successful but was: $value" }
            )
        }
    }
    
    /**
     * Matcher for failed pipeline execution.
     */
    fun beFailure() = object : io.kotest.matchers.Matcher<PipelineExecutionResult> {
        override fun test(value: PipelineExecutionResult): io.kotest.matchers.MatcherResult {
            return io.kotest.matchers.MatcherResult(
                value.isFailure,
                { "Pipeline execution should be failure but was: $value" },
                { "Pipeline execution should not be failure but was: $value" }
            )
        }
    }
    
    /**
     * Matcher for execution time.
     */
    fun executeInLessThan(maxTimeMs: Long) = object : io.kotest.matchers.Matcher<PipelineExecutionResult> {
        override fun test(value: PipelineExecutionResult): io.kotest.matchers.MatcherResult {
            val actualTime = when (value) {
                is PipelineExecutionResult.Success -> value.executionTimeMs
                is PipelineExecutionResult.Failure -> value.executionTimeMs
            }
            return io.kotest.matchers.MatcherResult(
                actualTime < maxTimeMs,
                { "Pipeline execution should take less than ${maxTimeMs}ms but took ${actualTime}ms" },
                { "Pipeline execution should take more than ${maxTimeMs}ms but took ${actualTime}ms" }
            )
        }
    }
}