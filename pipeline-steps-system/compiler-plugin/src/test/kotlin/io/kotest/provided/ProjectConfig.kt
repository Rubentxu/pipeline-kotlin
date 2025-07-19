package io.kotest.provided

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.spec.IsolationMode
import io.kotest.core.test.TestCaseOrder
import kotlin.time.Duration.Companion.minutes

/**
 * Kotest Project Configuration for Step Compiler Plugin Tests
 * 
 * Optimized for:
 * - IntelliJ IDEA compatibility and seamless test execution
 * - Compiler plugin testing with appropriate timeouts
 * - BDD test reliability and isolation
 * - Clean test output and debugging
 */
object ProjectConfig : AbstractProjectConfig() {
    
    // ============================================================================
    // TIMING CONFIGURATION
    // ============================================================================
    
    /**
     * Global timeout for all tests - generous for compiler testing
     */
    override val timeout = 5.minutes
    
    /**
     * Invocation timeout in milliseconds - allows for compilation time
     */
    override val invocationTimeout = 120_000L
    
    // ============================================================================
    // EXECUTION CONFIGURATION
    // ============================================================================
    
    /**
     * Isolation mode - fresh instance per test for reliability
     */
    override val isolationMode = IsolationMode.InstancePerTest
    
    /**
     * Test execution order - lexicographic for predictable debugging
     */
    override val testCaseOrder = TestCaseOrder.Lexicographic
    
    /**
     * Sequential execution to avoid compiler plugin conflicts
     */
    override val parallelism = 1
    
    // ============================================================================
    // PROJECT LIFECYCLE
    // ============================================================================
    
    /**
     * Clean startup message
     */
    override suspend fun beforeProject() {
        println("🚀 Starting Step Compiler Plugin BDD Tests")
    }
    
    /**
     * Clean completion message
     */
    override suspend fun afterProject() {
        println("✅ Step Compiler Plugin BDD Tests Completed")
    }
    
    // ============================================================================
    // EXTENSIONS CONFIGURATION
    // ============================================================================
    
    /**
     * No additional extensions needed for compiler plugin testing
     */
    override fun extensions(): List<Extension> = emptyList()
}