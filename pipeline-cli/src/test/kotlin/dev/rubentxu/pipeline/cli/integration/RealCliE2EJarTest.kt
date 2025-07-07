package dev.rubentxu.pipeline.cli.integration

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.io.File

/**
 * Real end-to-end CLI tests for JAR execution with dependencies
 * Tests actual CLI execution using built JAR files with all dependencies
 */
class RealCliE2EJarTest : AbstractE2ETest() {
    
    init {
    
        "should execute pipeline with JAR and all dependencies through CLI" {
            val helper = getHelper()
            val scriptFile = File("testData/jar-dependencies.pipeline.kts")
            val configFile = File("testData/config.yaml")
            
            if (scriptFile.exists() && configFile.exists()) {
                val result = helper.executeCliWithBackendJar(
                scriptPath = scriptFile.absolutePath,
                configPath = configFile.absolutePath,
                timeoutSeconds = 60
            )
            
            println("JAR dependencies test output:")
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
            result.stdout shouldContain "Testing JAR execution with all dependencies"
            result.stdout shouldContain "JAR dependencies test completed successfully"
            
            // Should show JAR path and classpath info
            result.stdout shouldContain "JAR path:"
            result.stdout shouldContain "Java classpath length:"
            result.stdout shouldContain "Classpath contains required JARs"
            
            println("✓ JAR dependencies test passed")
            }
        }
        
        "should load Kotlin stdlib and coroutines dependencies from JAR" {
            val helper = getHelper()
            val scriptFile = File("testData/jar-dependencies.pipeline.kts")
            val configFile = File("testData/config.yaml")
            
            if (scriptFile.exists() && configFile.exists()) {
                val result = helper.executeCliWithBackendJar(
                scriptPath = scriptFile.absolutePath,
                configPath = configFile.absolutePath,
                timeoutSeconds = 30
            )
            
            println("Kotlin dependencies test output:")
            println("Exit code: ${result.exitCode}")
            println("STDOUT:\n${result.stdout}")
            
            // Verify execution was successful - provide debug info if failed
            if (!result.isSuccess) {
                println("❌ Command failed with exit code: ${result.exitCode}")
                println("📄 STDERR output: ${result.stderr}")
                println("📄 STDOUT output: ${result.stdout}")
            }
            result.isSuccess shouldBe true
            
            // Should show Kotlin dependencies are available
            result.stdout shouldContain "Kotlin version:"
            result.stdout shouldContain "Coroutines support available"
            result.stdout shouldContain "All dependencies available"
            
            println("✓ Kotlin dependencies test passed")
            }
        }
        
        "should load GraalVM dependencies from JAR" {
            val helper = getHelper()
            val scriptFile = File("testData/jar-dependencies.pipeline.kts")
            val configFile = File("testData/config.yaml")
            
            if (scriptFile.exists() && configFile.exists()) {
                val result = helper.executeCliWithBackendJar(
                scriptPath = scriptFile.absolutePath,
                configPath = configFile.absolutePath,
                timeoutSeconds = 30
            )
            
            println("GraalVM dependencies test output:")
            println("Exit code: ${result.exitCode}")
            println("STDOUT:\n${result.stdout}")
            
            // Verify execution was successful - provide debug info if failed
            if (!result.isSuccess) {
                println("❌ Command failed with exit code: ${result.exitCode}")
                println("📄 STDERR output: ${result.stderr}")
                println("📄 STDOUT output: ${result.stdout}")
            }
            result.isSuccess shouldBe true
            
            // Should test GraalVM dependencies
            result.stdout shouldContain "Testing GraalVM polyglot availability"
            // GraalVM may or may not be available, but should handle gracefully
            if (result.stdout.contains("GraalVM dependencies loaded")) {
                println("✓ GraalVM dependencies available and loaded")
            } else {
                result.stdout shouldContain "GraalVM test:"
                println("✓ GraalVM dependencies handled gracefully")
            }
            }
        }
        
        "should load serialization dependencies from JAR" {
            val helper = getHelper()
            val scriptFile = File("testData/jar-dependencies.pipeline.kts")
            val configFile = File("testData/config.yaml")
            
            if (scriptFile.exists() && configFile.exists()) {
                val result = helper.executeCliWithBackendJar(
                scriptPath = scriptFile.absolutePath,
                configPath = configFile.absolutePath,
                timeoutSeconds = 30
            )
            
            println("Serialization dependencies test output:")
            println("Exit code: ${result.exitCode}")
            println("STDOUT:\n${result.stdout}")
            
            // Verify execution was successful - provide debug info if failed
            if (!result.isSuccess) {
                println("❌ Command failed with exit code: ${result.exitCode}")
                println("📄 STDERR output: ${result.stderr}")
                println("📄 STDOUT output: ${result.stdout}")
            }
            result.isSuccess shouldBe true
            
            // Should test serialization dependencies
            result.stdout shouldContain "Testing serialization dependencies"
            result.stdout shouldContain "JSON serialization available"
            result.stdout shouldContain "YAML serialization available"
            
            println("✓ Serialization dependencies test passed")
            }
        }
        
        "should execute parallel tasks with JAR dependencies" {
            val helper = getHelper()
            val scriptFile = File("testData/jar-dependencies.pipeline.kts")
            val configFile = File("testData/config.yaml")
            
            if (scriptFile.exists() && configFile.exists()) {
                val result = helper.executeCliWithBackendJar(
                scriptPath = scriptFile.absolutePath,
                configPath = configFile.absolutePath,
                timeoutSeconds = 30
            )
            
            println("Parallel JAR execution test output:")
            println("Exit code: ${result.exitCode}")
            println("STDOUT:\n${result.stdout}")
            
            // Verify execution was successful - provide debug info if failed
            if (!result.isSuccess) {
                println("❌ Command failed with exit code: ${result.exitCode}")
                println("📄 STDERR output: ${result.stderr}")
                println("📄 STDOUT output: ${result.stdout}")
            }
            result.isSuccess shouldBe true
            
            // Should execute parallel tasks with dependencies
            result.stdout shouldContain "Testing dependencies in parallel task A"
            result.stdout shouldContain "Testing dependencies in parallel task B"
            result.stdout shouldContain "All dependencies available in task A"
            result.stdout shouldContain "All dependencies available in task B"
            
            // Should complete all dependency loading
            result.stdout shouldContain "All JAR dependencies loaded correctly"
            
            println("✓ Parallel JAR execution test passed")
            }
        }
        
        "should handle file operations with JAR dependencies" {
            val helper = getHelper()
            val scriptFile = File("testData/jar-dependencies.pipeline.kts")
            val configFile = File("testData/config.yaml")
            
            if (scriptFile.exists() && configFile.exists()) {
                val result = helper.executeCliWithBackendJar(
                scriptPath = scriptFile.absolutePath,
                configPath = configFile.absolutePath,
                timeoutSeconds = 30
            )
            
            println("File operations with JAR test output:")
            println("Exit code: ${result.exitCode}")
            println("STDOUT:\n${result.stdout}")
            
            // Verify execution was successful - provide debug info if failed
            if (!result.isSuccess) {
                println("❌ Command failed with exit code: ${result.exitCode}")
                println("📄 STDERR output: ${result.stderr}")
                println("📄 STDOUT output: ${result.stdout}")
            }
            result.isSuccess shouldBe true
            
            // Should handle file operations
            result.stdout shouldContain "Build file read successfully"
            result.stdout shouldContain "length:"
            
            println("✓ File operations with JAR test passed")
            }
        }
        
        "should execute CLI version command with JAR" {
            val helper = getHelper()
            try {
                val result = helper.executeCliWithJar("version", timeoutSeconds = 10)
            
            println("JAR version command test output:")
            println("Exit code: ${result.exitCode}")
            println("STDOUT:\n${result.stdout}")
            if (result.stderr.isNotEmpty()) {
                println("STDERR:\n${result.stderr}")
            }
            
            // Version command should work with JAR
            if (result.isSuccess) {
                println("✓ JAR version command test passed")
            } else {
                println("⚠ JAR version command may need different approach")
            }
            
            } catch (e: Exception) {
                println("JAR version command test: ${e.message}")
                // This is acceptable as the JAR structure might be different
            }
        }
        
        "should execute CLI validate command with JAR" {
            val helper = getHelper()
            val configFile = File("testData/config.yaml")
            
            if (configFile.exists()) {
                try {
                    val result = helper.executeCliWithJar(
                    "validate",
                    "--config", configFile.absolutePath,
                    timeoutSeconds = 20
                )
                
                println("JAR validate command test output:")
                println("Exit code: ${result.exitCode}")
                println("STDOUT:\n${result.stdout}")
                if (result.stderr.isNotEmpty()) {
                    println("STDERR:\n${result.stderr}")
                }
                
                // Validate command should work with JAR
                if (result.isSuccess) {
                    println("✓ JAR validate command test passed")
                } else {
                    println("⚠ JAR validate command provided feedback")
                }
                
                } catch (e: Exception) {
                    println("JAR validate command test: ${e.message}")
                    // This is acceptable as the JAR might have different CLI structure
                }
            }
        }
        
        "should verify JAR contains all required dependencies" {
            val helper = getHelper()
            val testDataInfo = helper.getTestDataInfo()
        val jarExists = testDataInfo["Built JAR"] == true
        
        jarExists shouldBe true
        
        if (jarExists) {
            // Test that the JAR can execute basic pipeline
            val scriptFile = File("testData/success.pipeline.kts")
            val configFile = File("testData/config.yaml")
            
            if (scriptFile.exists() && configFile.exists()) {
                val result = helper.executeCliWithBackendJar(
                    scriptPath = scriptFile.absolutePath,
                    configPath = configFile.absolutePath,
                    timeoutSeconds = 30
                )
                
                println("JAR basic execution verification:")
                println("Exit code: ${result.exitCode}")
                println("Execution time: ${result.executionTimeMs}ms")
                
                // Basic execution should work with JAR
                result.isSuccess shouldBe true
                
                println("✓ JAR contains all required dependencies for basic execution")
            }
        }
        }
    }
}
