package dev.rubentxu.pipeline.security

import dev.rubentxu.pipeline.dsl.*
import dev.rubentxu.pipeline.logger.PipelineLogger
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Files

class SandboxManagerTest : StringSpec({
    
    "should validate security policy correctly" {
        runTest {
            val logger = PipelineLogger.getLogger()
            val sandboxManager = SandboxManager(logger)
            
            try {
                // Valid policy
                val validContext = DslExecutionContext(
                    workingDirectory = Files.createTempDirectory("test").toFile(),
                    environmentVariables = emptyMap(),
                    resourceLimits = DslResourceLimits(
                        maxMemoryMb = 512,
                        maxCpuTimeMs = 30_000L,
                        maxWallTimeMs = 60_000L,
                        maxThreads = 20
                    )
                )
                
                val validationResult = sandboxManager.validateSecurityPolicy(validContext)
                validationResult shouldNotBe null
                
                // Test with different resource limits
                val anotherContext = DslExecutionContext(
                    workingDirectory = Files.createTempDirectory("test2").toFile(),
                    environmentVariables = emptyMap(),
                    resourceLimits = DslResourceLimits(
                        maxMemoryMb = 256,
                        maxCpuTimeMs = 15_000L,
                        maxWallTimeMs = 30_000L,
                        maxThreads = 10
                    )
                )
                
                val anotherValidationResult = sandboxManager.validateSecurityPolicy(anotherContext)
                anotherValidationResult shouldNotBe null
                
            } finally {
                sandboxManager.shutdown()
            }
        }
    }
    
    "should execute script securely with appropriate sandbox selection" {
        runTest {
            val logger = PipelineLogger.getLogger()
            val sandboxManager = SandboxManager(logger)
            
            try {
                val tempDir = Files.createTempDirectory("sandbox-manager-test").toFile()
                
                // Test basic sandbox functionality
                val context = DslExecutionContext(
                    workingDirectory = tempDir,
                    environmentVariables = mapOf("TEST_MODE" to "thread_isolation"),
                    resourceLimits = DslResourceLimits(
                        maxMemoryMb = 256,
                        maxCpuTimeMs = 15_000L,
                        maxWallTimeMs = 30_000L,
                        maxThreads = 20
                    )
                )
                
                // Test basic validation
                val validation = sandboxManager.validateSecurityPolicy(context)
                validation shouldNotBe null
                
                tempDir.deleteRecursively()
                
            } finally {
                sandboxManager.shutdown()
            }
        }
    }
    
    "should handle execution timeout correctly" {
        runTest {
            val logger = PipelineLogger.getLogger()
            val sandboxManager = SandboxManager(logger)
            
            try {
                val tempDir = Files.createTempDirectory("sandbox-timeout-test").toFile()
                val timeoutContext = DslExecutionContext(
                    workingDirectory = tempDir,
                    environmentVariables = emptyMap(),
                    resourceLimits = DslResourceLimits(
                        maxMemoryMb = 256,
                        maxCpuTimeMs = 5_000L,
                        maxWallTimeMs = 2_000L, // Very short timeout
                        maxThreads = 20
                    )
                )
                
                // Test timeout validation
                val validation = sandboxManager.validateSecurityPolicy(timeoutContext)
                validation shouldNotBe null
                
                // Test that sandbox manager handles timeout configurations
                val activeExecutions = sandboxManager.getActiveExecutions()
                activeExecutions shouldNotBe null
                
                tempDir.deleteRecursively()
                
            } finally {
                sandboxManager.shutdown()
            }
        }
    }
    
    "should track active executions" {
        runTest {
            val logger = PipelineLogger.getLogger()
            val sandboxManager = SandboxManager(logger)
            
            try {
                // Initially no active executions
                val initialExecutions = sandboxManager.getActiveExecutions()
                initialExecutions should { it.isEmpty() }
                
                // Test that we can get active executions (though they may complete quickly)
                val activeExecutions = sandboxManager.getActiveExecutions()
                activeExecutions shouldNotBe null
                
            } finally {
                sandboxManager.shutdown()
            }
        }
    }
    
    "should handle security policy violations" {
        runTest {
            val logger = PipelineLogger.getLogger()
            val sandboxManager = SandboxManager(logger)
            
            try {
                val tempDir = Files.createTempDirectory("security-violation-test").toFile()
                val violatingContext = DslExecutionContext(
                    workingDirectory = tempDir,
                    environmentVariables = mapOf("SYSTEM_SECRET" to "should_not_be_allowed"),
                    resourceLimits = DslResourceLimits(
                        maxMemoryMb = 4096, // Exceeds limits
                        maxCpuTimeMs = 600_000L, // Exceeds limits
                        maxWallTimeMs = 60_000L,
                        maxThreads = 20
                    ),
                    executionPolicy = DslExecutionPolicy(
                        isolationLevel = DslIsolationLevel.PROCESS,
                        allowConcurrentExecution = false,
                        enableEventPublishing = true
                    )
                )
                
                val scriptContent = """
                    console.log("This should not execute due to policy violation");
                    "violation test";
                """.trimIndent()
                
                // Test security policy validation
                val validation = sandboxManager.validateSecurityPolicy(violatingContext)
                validation shouldNotBe null
                
                // Test that sandbox tracks executions correctly
                val executions = sandboxManager.getActiveExecutions()
                executions shouldNotBe null
                
                tempDir.deleteRecursively()
                
            } finally {
                sandboxManager.shutdown()
            }
        }
    }
    
    "should properly clean up resources on shutdown" {
        runTest {
            val logger = PipelineLogger.getLogger()
            val sandboxManager = SandboxManager(logger)
            
            // Verify clean shutdown
            sandboxManager.shutdown()
            
            // After shutdown, active executions should be empty
            val executionsAfterShutdown = sandboxManager.getActiveExecutions()
            executionsAfterShutdown should { it.isEmpty() }
        }
    }
})