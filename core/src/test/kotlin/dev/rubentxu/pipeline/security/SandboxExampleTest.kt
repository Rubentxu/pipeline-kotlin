package dev.rubentxu.pipeline.security

import dev.rubentxu.pipeline.dsl.*
import dev.rubentxu.pipeline.logger.PipelineLogger
import dev.rubentxu.pipeline.model.config.IPipelineConfig
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files

/**
 * Example test demonstrating the GraalVM Isolate sandbox functionality
 */
class SandboxExampleTest : StringSpec({
    
    "should demonstrate secure execution with resource monitoring" {
        runTest {
            val logger = PipelineLogger.getLogger()
            
            // Create a mock config
            val config = object : IPipelineConfig {}
            
            val dslManager = DslManager(config, logger = logger)
            
            try {
                val tempDir = Files.createTempDirectory("sandbox-example").toFile()
                
                // Test basic DslManager functionality
                val engines = dslManager.getEngineInfo()
                engines.isNotEmpty() shouldBe true
                
                logger.info("🔒 Security sandbox test - DslManager initialized")
                
                // Test sandbox manager is available
                val sandboxManager = dslManager.sandboxManager
                sandboxManager shouldNotBe null
                
                logger.info("✅ Security sandbox test completed successfully")
                
                tempDir.deleteRecursively()
                
            } finally {
                dslManager.shutdown()
            }
        }
    }
    
    "should demonstrate different isolation levels" {
        runTest {
            val logger = PipelineLogger.getLogger()
            
            val config = object : IPipelineConfig {}
            
            val dslManager = DslManager(config, logger = logger)
            
            try {
                val tempDir = Files.createTempDirectory("isolation-levels").toFile()
                
                // Test Thread-level isolation context creation
                logger.info("🧵 Testing THREAD-level isolation")
                val threadContext = DslExecutionContext(
                    workingDirectory = tempDir,
                    environmentVariables = mapOf("ISOLATION_LEVEL" to "THREAD"),
                    resourceLimits = DslResourceLimits(
                        maxMemoryMb = 256,
                        maxCpuTimeMs = 15_000L,
                        maxWallTimeMs = 30_000L,
                        maxThreads = 20
                    ),
                    executionPolicy = DslExecutionPolicy(
                        isolationLevel = DslIsolationLevel.THREAD,
                        allowConcurrentExecution = false,
                        enableEventPublishing = true
                    )
                )
                
                threadContext.executionPolicy?.isolationLevel shouldBe DslIsolationLevel.THREAD
                logger.info("✅ Thread-level isolation context created")
                
                // Test Process-level isolation context creation
                logger.info("🔒 Testing PROCESS-level isolation")
                val processContext = DslExecutionContext(
                    workingDirectory = tempDir,
                    environmentVariables = mapOf("ISOLATION_LEVEL" to "PROCESS"),
                    resourceLimits = DslResourceLimits(
                        maxMemoryMb = 128,
                        maxCpuTimeMs = 10_000L,
                        maxWallTimeMs = 20_000L,
                        maxThreads = 20
                    ),
                    executionPolicy = DslExecutionPolicy(
                        isolationLevel = DslIsolationLevel.PROCESS,
                        allowConcurrentExecution = false,
                        enableEventPublishing = true
                    )
                )
                
                processContext.executionPolicy?.isolationLevel shouldBe DslIsolationLevel.PROCESS
                logger.info("🔍 Process-level isolation context created")
                
                tempDir.deleteRecursively()
                
            } finally {
                dslManager.shutdown()
            }
        }
    }
    
    "should demonstrate security policy validation" {
        runTest {
            val logger = PipelineLogger.getLogger()
            val sandboxManager = SandboxManager(logger)
            
            try {
                logger.info("🛡️ Testing security policy validation")
                
                // Valid policy
                val validPolicy = DslExecutionContext(
                    workingDirectory = Files.createTempDirectory("valid-policy-test").toFile(),
                    environmentVariables = emptyMap(),
                    resourceLimits = DslResourceLimits(
                        maxMemoryMb = 256,
                        maxCpuTimeMs = 15_000L,
                        maxWallTimeMs = 30_000L,
                        maxThreads = 20
                    )
                )
                
                // Test sandbox manager basic functionality
                val activeExecutions = sandboxManager.getActiveExecutions()
                activeExecutions shouldNotBe null
                
                // Test that validation method exists and returns a result
                val validationResult = sandboxManager.validateSecurityPolicy(validPolicy)
                validationResult shouldNotBe null
                
                logger.info("✅ Security policy validation test completed")
                
            } finally {
                sandboxManager.shutdown()
            }
        }
    }
})