package dev.rubentxu.pipeline.cli.integration

import io.kotest.core.spec.style.StringSpec
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Base class for E2E tests with proper resource isolation and sequential execution
 * Only applies synchronization to tests that need external CLI JAR resources
 */
abstract class AbstractE2ETest : StringSpec() {
    
    companion object {
        // Lock specifically for CLI JAR resource conflicts (only for external process tests)
        private val cliJarResourceLock = ReentrantLock()
        
        // Counter to track CLI resource test execution order
        private var cliTestCounter = 0
    }
    
    protected var cliHelper: CliTestHelper? = null
    
    init {
        beforeEach {
            // Only use synchronization for tests that actually use external CLI processes
            // Tests that use CliTestHelper need synchronization due to JAR file access
            cliJarResourceLock.withLock {
                val testNumber = ++cliTestCounter
                println("=== Starting CLI E2E Test #$testNumber ===")
                
                // Create isolated helper for each test
                cliHelper = CliTestHelper()
                
                // Ensure CLI JAR is available in isolated environment
                cliHelper?.ensureCliJarExists()
                
                // Print test data availability for debugging
                val testDataInfo = cliHelper?.getTestDataInfo() ?: emptyMap()
                println("Test isolation setup:")
                testDataInfo.forEach { (name: String, available: Boolean) ->
                    println("${if (available) "✓" else "✗"} $name")
                }
                
                // Add small delay to ensure complete resource cleanup
                Thread.sleep(50)
            }
        }
        
        afterEach {
            cliJarResourceLock.withLock {
                // Clean up isolated resources
                cliHelper?.close()
                cliHelper = null
                
                // Add small delay to ensure complete resource cleanup
                Thread.sleep(50)
                
                println("=== CLI E2E Test completed, resources cleaned ===")
            }
        }
    }
    
    /**
     * Get the helper with null-safety check
     */
    protected fun getHelper(): CliTestHelper {
        return cliHelper ?: throw IllegalStateException("CliHelper not initialized")
    }
}