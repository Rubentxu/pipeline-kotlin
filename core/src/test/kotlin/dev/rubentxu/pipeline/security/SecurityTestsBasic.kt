package dev.rubentxu.pipeline.security

import dev.rubentxu.pipeline.dsl.*
import dev.rubentxu.pipeline.logger.IPipelineLogger
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.types.beInstanceOf
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import java.io.File

class SecurityTestsBasic : DescribeSpec({

    describe("Security Core - Basic Tests") {
        
        beforeEach {
            clearAllMocks()
        }
        
        afterEach {
            unmockkAll()
        }

        describe("SandboxManager Basic Tests") {
            it("should validate resource limits within acceptable bounds") {
                val mockLogger = mockk<IPipelineLogger>(relaxed = true)
                val sandboxManager = SandboxManager(mockLogger)
                
                val validExecutionContext = createBasicExecutionContext(
                    resourceLimits = DslResourceLimits(
                        maxMemoryMb = 1024,
                        maxCpuTimeMs = 120_000,
                        maxThreads = 5
                    )
                )
                
                val validation = sandboxManager.validateSecurityPolicy(validExecutionContext)
                validation.isValid shouldBe true
                validation.issues shouldHaveSize 0
            }

            it("should reject resource limits that exceed security bounds") {
                val mockLogger = mockk<IPipelineLogger>(relaxed = true)
                val sandboxManager = SandboxManager(mockLogger)
                
                val invalidExecutionContext = createBasicExecutionContext(
                    resourceLimits = DslResourceLimits(
                        maxMemoryMb = 10_000, // Too high
                        maxCpuTimeMs = 3_600_000, // 1 hour - too long
                        maxThreads = 100 // Too many
                    )
                )
                
                val validation = sandboxManager.validateSecurityPolicy(invalidExecutionContext)
                validation.isValid shouldBe false
                validation.issues.size shouldBe 3 // Memory, CPU, Threads violations
            }
            
            it("should have sandbox manager functionality") {
                val mockLogger = mockk<IPipelineLogger>(relaxed = true)
                val sandboxManager = SandboxManager(mockLogger)
                
                // Test that sandbox manager can be created
                sandboxManager shouldNotBe null
            }
        }

        describe("ProcessLevelSandbox Basic Tests") {
            it("should create sandbox with proper isolation") {
                val mockLogger = mockk<IPipelineLogger>(relaxed = true)
                val sandbox = ProcessLevelSandbox(mockLogger)
                
                // Test that sandbox can be created and cleaned up
                sandbox.cleanup()
                
                verify { mockLogger.info(match { it.contains("Shutting down process sandbox") }) }
            }
        }

        describe("GraalVMIsolateSandbox Basic Tests") {
            it("should create GraalVM sandbox with proper cleanup") {
                val mockLogger = mockk<IPipelineLogger>(relaxed = true)
                val sandbox = GraalVMIsolateSandbox(mockLogger)
                
                // Test that sandbox can be created and cleaned up
                sandbox.cleanup()
                
                verify { mockLogger.info(match { it.contains("Shutting down GraalVM sandbox") }) }
            }
        }
    }
})

private fun createBasicExecutionContext(
    resourceLimits: DslResourceLimits? = null,
    environmentVariables: Map<String, String> = emptyMap()
): DslExecutionContext {
    return DslExecutionContext(
        workingDirectory = File("/tmp/test"),
        environmentVariables = environmentVariables,
        executionPolicy = DslExecutionPolicy(
            isolationLevel = DslIsolationLevel.THREAD,
            allowConcurrentExecution = false,
            enableEventPublishing = true
        ),
        resourceLimits = resourceLimits ?: DslResourceLimits(
            maxMemoryMb = 512,
            maxCpuTimeMs = 30_000,
            maxWallTimeMs = 60_000,
            maxThreads = 2
        )
    )
}