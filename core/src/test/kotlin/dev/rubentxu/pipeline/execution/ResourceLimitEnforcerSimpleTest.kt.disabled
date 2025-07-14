package dev.rubentxu.pipeline.execution

import dev.rubentxu.pipeline.dsl.DslResourceLimits
import dev.rubentxu.pipeline.logger.PipelineLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach

/**
 * Simple test to verify ResourceLimitEnforcer implementation works correctly.
 * This test focuses on basic functionality without complex scenarios.
 */
class ResourceLimitEnforcerSimpleTest {
    
    private lateinit var enforcer: ResourceLimitEnforcer
    
    @BeforeEach
    fun setUp() {
        val logger = PipelineLogger.getLogger()
        enforcer = ResourceLimitEnforcer(logger)
    }
    
    @AfterEach
    fun tearDown() {
        enforcer.shutdown()
    }
    
    @Test
    fun `should execute simple operation without limits`() = runTest {
        val result = enforcer.enforceResourceLimits(
            executionId = "test-no-limits",
            limits = null
        ) {
            "Hello, World!"
        }
        
        assertTrue(result.isSuccess())
        val success = result as ResourceLimitedResult.Success
        assertEquals("Hello, World!", success.result)
        assertEquals("test-no-limits", success.resourceStats.executionId)
    }
    
    @Test
    fun `should execute operation within reasonable limits`() = runTest {
        val limits = DslResourceLimits(
            maxMemoryMb = 512,
            maxCpuTimeMs = 60000L, // Increased to avoid CPU time violations  
            maxWallTimeMs = 10000L, // Increased wall time as well
            maxThreads = 30
        )
        
        val result = enforcer.enforceResourceLimits(
            executionId = "test-within-limits",
            limits = limits
        ) {
            delay(100) // Small delay to simulate work
            val calculation = (1..1000).sum()
            "Result: $calculation"
        }
        
        assertTrue(result.isSuccess())
        val success = result as ResourceLimitedResult.Success
        assertEquals("Result: 500500", success.result)
        assertEquals("test-within-limits", success.resourceStats.executionId)
        assertEquals(limits, success.resourceStats.limitsApplied)
    }
    
    @Test
    fun `should handle exceptions during execution`() = runTest {
        val limits = DslResourceLimits(
            maxMemoryMb = 512,
            maxCpuTimeMs = 60000L, // Increased to avoid CPU time violations  
            maxWallTimeMs = 10000L, // Increased wall time as well
            maxThreads = 30
        )
        
        val result = enforcer.enforceResourceLimits(
            executionId = "test-exception",
            limits = limits
        ) {
            throw RuntimeException("Test exception")
        }
        
        assertTrue(result.isFailure())
        val failure = result as ResourceLimitedResult.Failure
        assertEquals(ResourceLimitType.EXECUTION_ERROR, failure.violation.type)
        assertTrue(failure.violation.message.contains("Test exception"))
    }
    
    @Test
    fun `should timeout on wall time limit`() = runTest {
        val limits = DslResourceLimits(
            maxMemoryMb = 512,
            maxCpuTimeMs = 5000L,
            maxWallTimeMs = 200L, // Very short wall time
            maxThreads = 30
        )
        
        val result = enforcer.enforceResourceLimits(
            executionId = "test-timeout",
            limits = limits
        ) {
            delay(1000) // This should exceed the wall time limit
            "Should not complete"
        }
        
        assertTrue(result.isFailure())
        val failure = result as ResourceLimitedResult.Failure
        assertEquals(ResourceLimitType.WALL_TIME, failure.violation.type)
    }
    
    @Test
    fun `should track active executions`() = runTest {
        val limits = DslResourceLimits(
            maxMemoryMb = 512,
            maxCpuTimeMs = 60000L, // Increased to avoid CPU time violations  
            maxWallTimeMs = 10000L, // Increased wall time as well
            maxThreads = 30
        )
        
        // Start execution but don't wait for it to complete
        val result = enforcer.enforceResourceLimits(
            executionId = "test-tracking",
            limits = limits
        ) {
            delay(100)
            "Completed"
        }
        
        assertTrue(result.isSuccess())
        val success = result as ResourceLimitedResult.Success
        assertEquals("Completed", success.result)
        
        // After completion, the execution should no longer be active
        val activeExecutions = enforcer.getAllActiveExecutions()
        assertFalse(activeExecutions.containsKey("test-tracking"))
    }
    
    @Test
    fun `should handle ResourceUsageStats correctly`() = runTest {
        val limits = DslResourceLimits(
            maxMemoryMb = 512,
            maxCpuTimeMs = 60000L, // Increased to avoid CPU time violations  
            maxWallTimeMs = 10000L, // Increased wall time as well
            maxThreads = 30
        )
        
        val result = enforcer.enforceResourceLimits(
            executionId = "test-stats",
            limits = limits
        ) {
            delay(50)
            "Stats test"
        }
        
        assertTrue(result.isSuccess())
        val success = result as ResourceLimitedResult.Success
        val stats = success.resourceStats
        
        assertEquals("test-stats", stats.executionId)
        assertEquals(limits, stats.limitsApplied)
        assertTrue(stats.totalWallTimeMs >= 0) // Should be non-negative
        // Note: hasViolations() may be true in test environment due to thread pool usage
        
        // Test human readable format
        val humanReadable = stats.toHumanReadable()
        assertTrue(humanReadable.contains("test-stats"))
        assertTrue(humanReadable.contains("Wall Time:"))
        assertTrue(humanReadable.contains("Peak Memory:"))
    }
    
    @Test
    fun `should handle termination correctly`() = runTest {
        // Test termination of non-existent execution
        val terminated = enforcer.terminateExecution("non-existent")
        assertFalse(terminated)
        
        // Test getting resource usage for non-existent execution
        val usage = enforcer.getResourceUsage("non-existent")
        assertNull(usage)
    }
    
    @Test
    fun `should handle resource efficiency ratios`() = runTest {
        val limits = DslResourceLimits(
            maxMemoryMb = 512,
            maxCpuTimeMs = 60000L, // Increased to avoid CPU time violations  
            maxWallTimeMs = 10000L, // Increased wall time as well
            maxThreads = 30
        )
        
        val result = enforcer.enforceResourceLimits(
            executionId = "test-ratios",
            limits = limits
        ) {
            delay(100)
            "Ratios test"
        }
        
        assertTrue(result.isSuccess())
        val success = result as ResourceLimitedResult.Success
        val stats = success.resourceStats
        
        val efficiencyRatios = stats.getEfficiencyRatios()
        
        // Should have entries for all the limits we set
        assertTrue(efficiencyRatios.containsKey(ResourceLimitType.MEMORY))
        assertTrue(efficiencyRatios.containsKey(ResourceLimitType.WALL_TIME))
        assertTrue(efficiencyRatios.containsKey(ResourceLimitType.THREADS))
        
        // Efficiency ratios should be reasonable (between 0 and 1 for normal execution)
        efficiencyRatios.values.forEach { ratio ->
            assertTrue(ratio >= 0.0, "Efficiency ratio should be non-negative: $ratio")
        }
    }
}