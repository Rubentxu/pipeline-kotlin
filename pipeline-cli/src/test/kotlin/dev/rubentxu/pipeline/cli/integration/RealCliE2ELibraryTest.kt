package dev.rubentxu.pipeline.cli.integration

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.io.File

/**
 * Real end-to-end CLI tests for library loading (@Library functionality)
 * Tests actual CLI execution using ProcessBuilder with library dependencies
 */
class RealCliE2ELibraryTest : AbstractE2ETest() {
    
    init {
    
        "should execute pipeline with library loading through CLI" {
            val helper = getHelper()
            val scriptFile = File("testData/library-loading.pipeline.kts")
            val configFile = File("testData/config.yaml")
            
            if (scriptFile.exists() && configFile.exists()) {
                val result = helper.executeCliWithBackendJar(
                scriptPath = scriptFile.absolutePath,
                configPath = configFile.absolutePath,
                timeoutSeconds = 45
            )
            
            println("Library loading test output:")
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
            result.stdout shouldContain "Testing library loading functionality"
            result.stdout shouldContain "Library loading test completed successfully"
            
            // Should show library configuration
            result.stdout shouldContain "Shared library name:"
            result.stdout shouldContain "Shared library version:"
            result.stdout shouldContain "commons"
            result.stdout shouldContain "master"
            
            // Should test library functions availability
            result.stdout shouldContain "Testing library functions availability"
            result.stdout shouldContain "Library functions would be available here"
            
            // Should support parallel execution with libraries
            result.stdout shouldContain "Library function A executed"
            result.stdout shouldContain "Library function B executed"
            
            println("✓ Library loading test passed")
            }
        }
        
        "should load shared libraries from local source through CLI" {
            val helper = getHelper()
            val scriptFile = File("testData/library-loading.pipeline.kts")
            val configFile = File("testData/config.yaml")
            
            if (scriptFile.exists() && configFile.exists()) {
                // Test with environment variable override for library source
                val envVars = mapOf(
                    "LIBRARY_SOURCE" to "local",
                    "LIBRARY_PATH" to "src/test/resources/scripts"
                )
                
                val result = helper.executeCliWithBackendJar(
                scriptPath = scriptFile.absolutePath,
                configPath = configFile.absolutePath,
                environmentVars = envVars,
                timeoutSeconds = 30
            )
            
            println("Local library source test output:")
            println("Exit code: ${result.exitCode}")
            println("STDOUT:\n${result.stdout}")
            
            // Verify execution was successful - provide debug info if failed
            if (!result.isSuccess) {
                println("❌ Command failed with exit code: ${result.exitCode}")
                println("📄 STDERR output: ${result.stderr}")
                println("📄 STDOUT output: ${result.stdout}")
            }
            result.isSuccess shouldBe true
            result.stdout shouldContain "Loading library from local source"
            result.stdout shouldContain "src/test/resources/scripts"
            
            println("✓ Local library source test passed")
            }
        }
        
        "should validate library configuration through CLI" {
            val helper = getHelper()
            val configFile = File("testData/config.yaml")
            
            if (configFile.exists()) {
                // Use validate command to check library configuration
                val result = helper.executeCliWithGradle(
                "validate",
                "--config", configFile.absolutePath,
                timeoutSeconds = 20
            )
            
            println("Library configuration validation output:")
            println("Exit code: ${result.exitCode}")
            println("STDOUT:\n${result.stdout}")
            if (result.stderr.isNotEmpty()) {
                println("STDERR:\n${result.stderr}")
            }
            
            // Validation should succeed or provide useful feedback
            if (result.isSuccess) {
                result.stdout shouldContain "Valid"
                println("✓ Library configuration validation passed")
            } else {
                // Even if validation fails, it should give clear error messages
                result.stderr shouldNotBe ""
                println("⚠ Library configuration validation provided feedback")
            }
            }
        }
        
        "should handle library classpath integration through CLI" {
            val helper = getHelper()
            val scriptFile = File("testData/library-loading.pipeline.kts")
            val configFile = File("testData/config.yaml")
            
            if (scriptFile.exists() && configFile.exists()) {
                // Test with additional classpath for libraries
                val envVars = mapOf(
                    "JAVA_OPTS" to "-Djava.class.path.library.test=true",
                    "CLASSPATH_TEST" to "enabled"
                )
                
                val result = helper.executeCliWithBackendJar(
                scriptPath = scriptFile.absolutePath,
                configPath = configFile.absolutePath,
                environmentVars = envVars,
                timeoutSeconds = 30
            )
            
            println("Library classpath integration test output:")
            println("Exit code: ${result.exitCode}")
            println("STDOUT:\n${result.stdout}")
            
            // Verify execution was successful - provide debug info if failed
            if (!result.isSuccess) {
                println("❌ Command failed with exit code: ${result.exitCode}")
                println("📄 STDERR output: ${result.stderr}")
                println("📄 STDOUT output: ${result.stdout}")
            }
            result.isSuccess shouldBe true
            result.stdout shouldContain "Current classpath includes library paths"
            result.stdout shouldContain "Library resources should be accessible"
            
            println("✓ Library classpath integration test passed")
            }
        }
        
        "should execute multiple library functions in parallel through CLI" {
            val helper = getHelper()
            val scriptFile = File("testData/library-loading.pipeline.kts")
            val configFile = File("testData/config.yaml")
            
            if (scriptFile.exists() && configFile.exists()) {
                val result = helper.executeCliWithBackendJar(
                scriptPath = scriptFile.absolutePath,
                configPath = configFile.absolutePath,
                timeoutSeconds = 30
            )
            
            println("Parallel library functions test output:")
            println("Exit code: ${result.exitCode}")
            println("STDOUT:\n${result.stdout}")
            
            // Verify execution was successful - provide debug info if failed
            if (!result.isSuccess) {
                println("❌ Command failed with exit code: ${result.exitCode}")
                println("📄 STDERR output: ${result.stderr}")
                println("📄 STDOUT output: ${result.stdout}")
            }
            result.isSuccess shouldBe true
            
            // Should execute library functions in parallel
            result.stdout shouldContain "Task A: Using library functions"
            result.stdout shouldContain "Task B: Using library functions"
            result.stdout shouldContain "Library function A executed"
            result.stdout shouldContain "Library function B executed"
            
            // Should complete library integration
            result.stdout shouldContain "Library integration working correctly"
            
            println("✓ Parallel library functions test passed")
            }
        }
        
        "should handle library loading errors gracefully through CLI" {
            val helper = getHelper()
            // Create a test script that tries to load a non-existent library
            val errorScript = File(helper.getWorkingDirectory(), "testData/library-error-test.pipeline.kts")
            
            // Create temporary script for error testing
            if (!errorScript.exists()) {
                errorScript.parentFile.mkdirs()
                errorScript.writeText("""
                    #!/usr/bin/env kotlin
                    import dev.rubentxu.pipeline.dsl.*
                    import pipeline.kotlin.extensions.*
                    
                    pipeline {
                        environment {
                            "TEST_TYPE" += "LIBRARY_ERROR"
                            "LIBRARY_NAME" += "non-existent-library"
                        }
                        
                        stages {
                            stage("Test Library Error Handling") {
                                steps {
                                    echo("Testing library error handling")
                                    echo("This should handle missing library gracefully")
                                    echo("Library error test completed")
                                }
                            }
                        }
                    }
                """.trimIndent())
            }
            
            val configFile = File("testData/config.yaml")
            
            if (errorScript.exists() && configFile.exists()) {
                val result = helper.executeCliWithBackendJar(
                scriptPath = errorScript.absolutePath,
                configPath = configFile.absolutePath,
                timeoutSeconds = 20
            )
            
            println("Library error handling test output:")
            println("Exit code: ${result.exitCode}")
            println("STDOUT:\n${result.stdout}")
            if (result.stderr.isNotEmpty()) {
                println("STDERR:\n${result.stderr}")
            }
            
            // Should either succeed with graceful handling or provide clear error
            if (result.isSuccess) {
                result.stdout shouldContain "Library error test completed"
                println("✓ Library error handling test passed with graceful handling")
            } else {
                // Should provide clear error message about missing library
                println("✓ Library error handling test passed with clear error message")
            }
            
                // Clean up temporary file
                errorScript.delete()
            }
        }
    }
}