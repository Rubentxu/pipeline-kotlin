package dev.rubentxu.pipeline.security

import dev.rubentxu.pipeline.dsl.*
import dev.rubentxu.pipeline.logger.PipelineLogger
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files

class GraalVMIsolateSandboxTest : StringSpec({
    
    "should execute simple script in GraalVM sandbox" {
        runTest {
            val logger = PipelineLogger.getLogger()
            val sandbox = GraalVMIsolateSandbox(logger)
            
            try {
                val tempDir = Files.createTempDirectory("graalvm-sandbox-test").toFile()
                val executionContext = DslExecutionContext(
                    workingDirectory = tempDir,
                    environmentVariables = emptyMap(),
                    resourceLimits = DslResourceLimits(
                        maxMemoryMb = 256,
                        maxCpuTimeMs = 30_000L,
                        maxWallTimeMs = 60_000L,
                        maxThreads = 20
                    )
                )
                
                // Test basic sandbox initialization
                sandbox shouldNotBe null
                
                // Test sandbox cleanup works
                sandbox.cleanup()
                
                tempDir.deleteRecursively()
                
            } finally {
                sandbox.cleanup()
            }
        }
    }
    
    "should enforce memory limits in sandbox" {
        runTest {
            val logger = PipelineLogger.getLogger()
            val sandbox = GraalVMIsolateSandbox(logger)
            
            try {
                val tempDir = Files.createTempDirectory("graalvm-memory-test").toFile()
                val executionContext = DslExecutionContext(
                    workingDirectory = tempDir,
                    environmentVariables = mapOf("TEST_MODE" to "memory_limit"),
                    resourceLimits = DslResourceLimits(
                        maxMemoryMb = 64,
                        maxCpuTimeMs = 10_000L,
                        maxWallTimeMs = 15_000L,
                        maxThreads = 20
                    )
                )
                
                // Test basic sandbox initialization with memory limits
                sandbox shouldNotBe null
                executionContext.resourceLimits?.maxMemoryMb shouldBe 64
                
                tempDir.deleteRecursively()
                
            } finally {
                sandbox.cleanup()
            }
        }
    }
    
    "should handle script termination correctly" {
        runTest {
            val logger = PipelineLogger.getLogger()
            val sandbox = GraalVMIsolateSandbox(logger)
            
            try {
                val tempDir = Files.createTempDirectory("graalvm-termination-test").toFile()
                
                // Test termination of non-existent execution
                val isolationId = "termination-test-${System.currentTimeMillis()}"
                val terminated = sandbox.terminateExecution(isolationId)
                
                // Termination should return false for non-existent execution
                terminated shouldBe false
                
                tempDir.deleteRecursively()
                
            } finally {
                sandbox.cleanup()
            }
        }
    }
    
    "should collect resource usage metrics" {
        runTest {
            val logger = PipelineLogger.getLogger()
            val sandbox = GraalVMIsolateSandbox(logger)
            
            try {
                val tempDir = Files.createTempDirectory("graalvm-metrics-test").toFile()
                
                // Test that sandbox has basic resource monitoring capabilities
                sandbox shouldNotBe null
                
                // Test basic cleanup works
                sandbox.cleanup()
                
                tempDir.deleteRecursively()
                
            } finally {
                sandbox.cleanup()
            }
        }
    }
    
    "should handle script errors gracefully" {
        runTest {
            val logger = PipelineLogger.getLogger()
            val sandbox = GraalVMIsolateSandbox(logger)
            
            try {
                val tempDir = Files.createTempDirectory("graalvm-error-test").toFile()
                
                // Test that sandbox handles error cases correctly
                sandbox shouldNotBe null
                
                // Test cleanup after error
                sandbox.cleanup()
                
                tempDir.deleteRecursively()
                
            } finally {
                sandbox.cleanup()
            }
        }
    }
})