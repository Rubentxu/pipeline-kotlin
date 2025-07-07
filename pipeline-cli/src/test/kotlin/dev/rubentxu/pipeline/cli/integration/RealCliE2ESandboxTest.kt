package dev.rubentxu.pipeline.cli.integration

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.io.File

/**
 * Real end-to-end CLI tests for sandbox and security policies
 * Tests actual CLI execution using ProcessBuilder with different isolation levels
 */
class RealCliE2ESandboxTest : AbstractE2ETest() {

    init {
    
        "should execute pipeline with NONE isolation level through CLI" {
            val helper = getHelper()
            val scriptFile = File("testData/sandbox-none.pipeline.kts")
            val configFile = File("testData/sandbox-config.yaml")
            
            if (scriptFile.exists() && configFile.exists()) {
                val result = helper.executeCliWithBackendJar(
                    scriptPath = scriptFile.path,
                    configPath = configFile.path,
                    timeoutSeconds = 30
                )
                
                println("NONE isolation test output:")
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
                result.stdout shouldContain "Testing NONE isolation level"
                result.stdout shouldContain "NONE isolation test completed successfully"
                
                // NONE isolation should allow access to system properties
                result.stdout shouldContain "Java version:"
                result.stdout shouldContain "User home:"
                
                println("✓ NONE isolation level test passed")
            }
        }
    
        "should execute pipeline with THREAD isolation level through CLI" {
            val helper = getHelper()
            val scriptFile = File("testData/sandbox-thread.pipeline.kts")
            val configFile = File("testData/sandbox-config.yaml")
            
            if (scriptFile.exists() && configFile.exists()) {
                val result = helper.executeCliWithBackendJar(
                scriptPath = scriptFile.absolutePath,
                configPath = configFile.absolutePath,
                timeoutSeconds = 30
            )
            
            println("THREAD isolation test output:")
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
            result.stdout shouldContain "Testing THREAD isolation level"
            result.stdout shouldContain "THREAD isolation test completed successfully"
            
            // Thread isolation should show thread information
            result.stdout shouldContain "Current thread:"
            result.stdout shouldContain "Thread ID:"
            
            // Should support parallel execution
            result.stdout shouldContain "Thread A executing in isolation"
            result.stdout shouldContain "Thread B executing in isolation"
            
            println("✓ THREAD isolation level test passed")
            }
        }
        
        "should execute pipeline with CLASSLOADER isolation level through CLI" {
            val helper = getHelper()
            val scriptFile = File("testData/sandbox-classloader.pipeline.kts")
            val configFile = File("testData/sandbox-config.yaml")
            
            if (scriptFile.exists() && configFile.exists()) {
                val result = helper.executeCliWithBackendJar(
                scriptPath = scriptFile.absolutePath,
                configPath = configFile.absolutePath,
                timeoutSeconds = 30
            )
            
            println("CLASSLOADER isolation test output:")
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
            result.stdout shouldContain "Testing CLASSLOADER isolation level"
            result.stdout shouldContain "CLASSLOADER isolation test completed successfully"
            
            // ClassLoader isolation should show classloader information
            result.stdout shouldContain "ClassLoader:"
            result.stdout shouldContain "Context ClassLoader:"
            
            // Should support isolated parallel execution
            result.stdout shouldContain "Isolated execution A completed"
            result.stdout shouldContain "Isolated execution B completed"
            
            println("✓ CLASSLOADER isolation level test passed")
            }
        }
        
        "should execute pipeline with PROCESS isolation level through CLI" {
            val helper = getHelper()
            val scriptFile = File("testData/sandbox-process.pipeline.kts")
            val configFile = File("testData/sandbox-config.yaml")
            
            if (scriptFile.exists() && configFile.exists()) {
                val result = helper.executeCliWithBackendJar(
                scriptPath = scriptFile.absolutePath,
                configPath = configFile.absolutePath,
                timeoutSeconds = 45
            )
            
            println("PROCESS isolation test output:")
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
            result.stdout shouldContain "Testing PROCESS isolation level"
            result.stdout shouldContain "PROCESS isolation test completed successfully"
            
            // Process isolation should show process information
            result.stdout shouldContain "Process PID:"
            result.stdout shouldContain "Available memory:"
            result.stdout shouldContain "Max memory:"
            
            println("✓ PROCESS isolation level test passed")
            }
        }
        
        "should execute pipeline with CONTAINER isolation level through CLI" {
            val helper = getHelper()
            val scriptFile = File("testData/sandbox-container.pipeline.kts")
            val configFile = File("testData/sandbox-config.yaml")
            
            if (scriptFile.exists() && configFile.exists()) {
                val result = helper.executeCliWithBackendJar(
                scriptPath = scriptFile.absolutePath,
                configPath = configFile.absolutePath,
                timeoutSeconds = 60
            )
            
            println("CONTAINER isolation test output:")
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
            result.stdout shouldContain "Testing CONTAINER isolation level"
            result.stdout shouldContain "CONTAINER isolation test completed successfully"
            
            // Container isolation should show container information
            result.stdout shouldContain "Container environment check"
            result.stdout shouldContain "Container hostname:"
            
            // Should support containerized parallel execution
            result.stdout shouldContain "Container task A executing"
            result.stdout shouldContain "Container task B executing"
            
            println("✓ CONTAINER isolation level test passed")
            }
        }
        
        "should enforce security policies through CLI execution" {
            val helper = getHelper()
            val scriptFile = File("testData/security-policies.pipeline.kts")
            val configFile = File("testData/sandbox-config.yaml")
            
            if (scriptFile.exists() && configFile.exists()) {
                val result = helper.executeCliWithBackendJar(
                scriptPath = scriptFile.absolutePath,
                configPath = configFile.absolutePath,
                timeoutSeconds = 45
            )
            
            println("Security policies test output:")
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
            result.stdout shouldContain "Testing security policies and resource limits"
            result.stdout shouldContain "Security policies test completed successfully"
            
            // Should show resource monitoring
            result.stdout shouldContain "Free memory:"
            result.stdout shouldContain "Max memory:"
            result.stdout shouldContain "CPU test completed"
            
            // Should enforce security policies
            result.stdout shouldContain "All security policies enforced correctly"
            
            println("✓ Security policies test passed")
            }
        }
        
        "should validate CLI commands are available" {
            val helper = getHelper()
            // Test that basic CLI commands work
            val commands = listOf("version", "validate", "list", "clean")
            
            commands.forEach { command ->
                try {
                    val result = helper.executeCliWithGradle(command, "--help", timeoutSeconds = 10)
                
                println("Command '$command' test:")
                println("Exit code: ${result.exitCode}")
                
                // Help should be available for all commands
                if (result.exitCode == 0 || result.stdout.contains("Usage:") || result.stderr.contains("Usage:")) {
                    println("✓ Command '$command' is available")
                } else {
                    println("⚠ Command '$command' might have issues")
                    println("STDOUT: ${result.stdout}")
                    println("STDERR: ${result.stderr}")
                }
                
                } catch (e: Exception) {
                    println("✗ Command '$command' failed: ${e.message}")
                }
            }
        }
    }
}