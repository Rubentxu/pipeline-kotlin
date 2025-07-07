package dev.rubentxu.pipeline.cli.integration

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.io.File

/**
 * Real end-to-end CLI tests for specific Pipeline DSL features
 * Tests actual CLI execution of advanced DSL functionality
 */
class RealCliE2EDslFeaturesTest : AbstractE2ETest() {
    
    init {
    
        "should execute comprehensive DSL features through CLI" {
            val helper = getHelper()
            val scriptFile = File("testData/dsl-features.pipeline.kts")
            val configFile = File("testData/config.yaml")
            
            if (scriptFile.exists() && configFile.exists()) {
                val result = helper.executeCliWithBackendJar(
                scriptPath = scriptFile.absolutePath,
                configPath = configFile.absolutePath,
                timeoutSeconds = 60
            )
            
            println("DSL features comprehensive test output:")
            println("Exit code: ${result.exitCode}")
            println("STDOUT:\n${result.stdout}")
            if (result.stderr.isNotEmpty()) {
                println("STDERR:\n${result.stderr}")
            }
            
            // Verify execution was successful - provide debug info if failed
            if (!result.isSuccess) {
                println("❌ Command failed with exit code: ${result.exitCode}")
                println("📄 STDERR output: ${result.stderr}")
                println("📄 STDOUT output: ${result.stdout}")
            }
            result.isSuccess shouldBe true
            result.stdout shouldContain "DSL Features Test Summary"
            result.stdout shouldContain "All DSL features passed"
            
            println("✓ Comprehensive DSL features test passed")
            }
        }
        
        "should execute parallel branches through CLI" {
            val helper = getHelper()
            val scriptFile = File("testData/dsl-features.pipeline.kts")
            val configFile = File("testData/config.yaml")
            
            if (scriptFile.exists() && configFile.exists()) {
                val result = helper.executeCliWithBackendJar(
                scriptPath = scriptFile.absolutePath,
                configPath = configFile.absolutePath,
                timeoutSeconds = 45
            )
            
            println("Parallel execution test output:")
            println("Exit code: ${result.exitCode}")
            println("STDOUT:\n${result.stdout}")
            
            // Verify execution was successful - provide debug info if failed
            if (!result.isSuccess) {
                println("❌ Command failed with exit code: ${result.exitCode}")
                println("📄 STDERR output: ${result.stderr}")
                println("📄 STDOUT output: ${result.stdout}")
            }
            result.isSuccess shouldBe true
            result.stdout shouldContain "Testing parallel execution capabilities"
            
            // Should execute all parallel branches
            result.stdout shouldContain "Feature A: Testing echo functionality"
            result.stdout shouldContain "Feature B: Testing file operations"
            result.stdout shouldContain "Feature C: Testing environment variables"
            
            // Should complete all parallel tasks
            result.stdout shouldContain "Feature A: Delayed execution completed"
            result.stdout shouldContain "Feature B: File operations test"
            result.stdout shouldContain "Feature C: Environment test"
            
            result.stdout shouldContain "Parallel execution test completed successfully"
            result.stdout shouldContain "✓ Parallel execution with multiple branches"
            
            println("✓ Parallel branches execution test passed")
            }
        }
        
        "should execute retry mechanism through CLI" {
            val helper = getHelper()
            val scriptFile = File("testData/dsl-features.pipeline.kts")
            val configFile = File("testData/config.yaml")
            
            if (scriptFile.exists() && configFile.exists()) {
                val result = helper.executeCliWithBackendJar(
                scriptPath = scriptFile.absolutePath,
                configPath = configFile.absolutePath,
                timeoutSeconds = 30
            )
            
            println("Retry mechanism test output:")
            println("Exit code: ${result.exitCode}")
            println("STDOUT:\n${result.stdout}")
            
            // Verify execution was successful - provide debug info if failed
            if (!result.isSuccess) {
                println("❌ Command failed with exit code: ${result.exitCode}")
                println("📄 STDERR output: ${result.stderr}")
                println("📄 STDOUT output: ${result.stdout}")
            }
            result.isSuccess shouldBe true
            result.stdout shouldContain "Testing retry functionality"
            result.stdout shouldContain "Retry attempt - this should succeed"
            result.stdout shouldContain "Retry delay completed"
            result.stdout shouldContain "Retry timestamp:"
            result.stdout shouldContain "Retry mechanism test completed successfully"
            result.stdout shouldContain "✓ Retry mechanism with delays"
            
            println("✓ Retry mechanism test passed")
            }
        }
        
        "should handle environment variables through CLI" {
            val helper = getHelper()
            val scriptFile = File("testData/dsl-features.pipeline.kts")
            val configFile = File("testData/config.yaml")
            
            if (scriptFile.exists() && configFile.exists()) {
                val envVars = mapOf(
                    "EXTRA_TEST_VAR" to "extra_value",
                    "CLI_TEST_MODE" to "enabled"
                )
                
                val result = helper.executeCliWithBackendJar(
                scriptPath = scriptFile.absolutePath,
                configPath = configFile.absolutePath,
                environmentVars = envVars,
                timeoutSeconds = 30
            )
            
            println("Environment variables test output:")
            println("Exit code: ${result.exitCode}")
            println("STDOUT:\n${result.stdout}")
            
            // Verify execution was successful - provide debug info if failed
            if (!result.isSuccess) {
                println("❌ Command failed with exit code: ${result.exitCode}")
                println("📄 STDERR output: ${result.stderr}")
                println("📄 STDOUT output: ${result.stdout}")
            }
            result.isSuccess shouldBe true
            result.stdout shouldContain "Testing environment variable handling"
            
            // Should show original environment variables
            result.stdout shouldContain "Original TEST_TYPE: DSL_FEATURES"
            result.stdout shouldContain "Original FEATURE_TEST: comprehensive"
            
            // Should show dynamic environment variables
            result.stdout shouldContain "Dynamic variable: dynamic_value"
            result.stdout shouldContain "Computed variable: computed_"
            
            // Should handle shell environment variables
            result.stdout shouldContain "Shell environment test:"
            
            result.stdout shouldContain "Environment variables test completed successfully"
            result.stdout shouldContain "✓ Environment variable handling"
            
            println("✓ Environment variables test passed")
            }
        }
        
        "should execute file operations through CLI" {
            val helper = getHelper()
            val scriptFile = File("testData/dsl-features.pipeline.kts")
            val configFile = File("testData/config.yaml")
            
            if (scriptFile.exists() && configFile.exists()) {
                val result = helper.executeCliWithBackendJar(
                scriptPath = scriptFile.absolutePath,
                configPath = configFile.absolutePath,
                timeoutSeconds = 30
            )
            
            println("File operations test output:")
            println("Exit code: ${result.exitCode}")
            println("STDOUT:\n${result.stdout}")
            
            // Verify execution was successful - provide debug info if failed
            if (!result.isSuccess) {
                println("❌ Command failed with exit code: ${result.exitCode}")
                println("📄 STDERR output: ${result.stderr}")
                println("📄 STDOUT output: ${result.stdout}")
            }
            result.isSuccess shouldBe true
            result.stdout shouldContain "Testing file operations"
            
            // Should handle file reading
            result.stdout shouldContain "Build file length"
            result.stdout shouldContain "Build file contains 'plugins'"
            
            // Should handle directory operations
            result.stdout shouldContain "Directory listing lines:"
            result.stdout shouldContain "Current directory:"
            
            result.stdout shouldContain "File operations test completed successfully"
            result.stdout shouldContain "✓ File operations and shell commands"
            
            println("✓ File operations test passed")
            }
        }
        
        "should execute post actions through CLI" {
            val helper = getHelper()
            val scriptFile = File("testData/dsl-features.pipeline.kts")
            val configFile = File("testData/config.yaml")
            
            if (scriptFile.exists() && configFile.exists()) {
                val result = helper.executeCliWithBackendJar(
                scriptPath = scriptFile.absolutePath,
                configPath = configFile.absolutePath,
                timeoutSeconds = 30
            )
            
            println("Post actions test output:")
            println("Exit code: ${result.exitCode}")
            println("STDOUT:\n${result.stdout}")
            
            // Verify execution was successful - provide debug info if failed
            if (!result.isSuccess) {
                println("❌ Command failed with exit code: ${result.exitCode}")
                println("📄 STDERR output: ${result.stderr}")
                println("📄 STDOUT output: ${result.stdout}")
            }
            result.isSuccess shouldBe true
            result.stdout shouldContain "Testing post action mechanisms"
            
            // Should execute stage post actions
            result.stdout shouldContain "Post Always: This should always execute"
            result.stdout shouldContain "Post Always: Stage completed at"
            result.stdout shouldContain "Post Success: Stage completed successfully"
            result.stdout shouldContain "Post Success: All DSL features working"
            
            // Should execute pipeline post actions
            result.stdout shouldContain "Pipeline Post Always: DSL features test completed"
            result.stdout shouldContain "Pipeline Post Always: All stages executed"
            result.stdout shouldContain "Pipeline Post Success: All DSL features passed"
            result.stdout shouldContain "Pipeline Post Success: Ready for production use"
            
            result.stdout shouldContain "✓ Post action mechanisms"
            
            println("✓ Post actions test passed")
            }
        }
        
        "should execute complex pipeline workflow through CLI" {
            val helper = getHelper()
            val scriptFile = File("testData/success.pipeline.kts")
            val configFile = File("testData/config.yaml")
            
            if (scriptFile.exists() && configFile.exists()) {
                val result = helper.executeCliWithBackendJar(
                scriptPath = scriptFile.absolutePath,
                configPath = configFile.absolutePath,
                timeoutSeconds = 45
            )
            
            println("Complex workflow test output:")
            println("Exit code: ${result.exitCode}")
            println("STDOUT:\n${result.stdout}")
            
            // Verify execution was successful - provide debug info if failed
            if (!result.isSuccess) {
                println("❌ Command failed with exit code: ${result.exitCode}")
                println("📄 STDERR output: ${result.stderr}")
                println("📄 STDOUT output: ${result.stdout}")
            }
            result.isSuccess shouldBe true
            
            // Should execute complex workflow with stages
            result.stdout shouldContain "Delay antes de ejecutar los pasos paralelos"
            result.stdout shouldContain "This is branch a"
            result.stdout shouldContain "This is branch b"
            result.stdout shouldContain "Tests retry"
            
            // Should show environment variable usage
            result.stdout shouldContain "Variable de entorno para DB_ENGINE es sqlite"
            
            // Should execute post actions
            result.stdout shouldContain "This is the post section always"
            result.stdout shouldContain "This is the post section success"
            
            println("✓ Complex pipeline workflow test passed")
            }
        }
        
        "should verify all DSL features are working through CLI" {
            val helper = getHelper()
            val scriptFile = File("testData/dsl-features.pipeline.kts")
            val configFile = File("testData/config.yaml")
            
            if (scriptFile.exists() && configFile.exists()) {
                val result = helper.executeCliWithBackendJar(
                scriptPath = scriptFile.absolutePath,
                configPath = configFile.absolutePath,
                timeoutSeconds = 60
            )
            
            println("Complete DSL features verification:")
            println("Exit code: ${result.exitCode}")
            println("Execution time: ${result.executionTimeMs}ms")
            
            // Verify all features were tested
            result.isSuccess shouldBe true
            
            // Check that all major DSL features are covered
            val requiredFeatures = listOf(
                "✓ Parallel execution with multiple branches",
                "✓ Retry mechanism with delays", 
                "✓ Environment variable handling",
                "✓ File operations and shell commands",
                "✓ Post action mechanisms"
            )
            
            requiredFeatures.forEach { feature ->
                result.stdout shouldContain feature
                println("Verified: $feature")
            }
            
            result.stdout shouldContain "Ready for production use"
            
            println("✓ All DSL features verified and working correctly")
            }
        }
    }
}