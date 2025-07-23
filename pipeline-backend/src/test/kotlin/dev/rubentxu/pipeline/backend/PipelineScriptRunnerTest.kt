package dev.rubentxu.pipeline.backend

import dev.rubentxu.pipeline.runner.Status
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.koin.core.context.stopKoin
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Test for Pipeline Script Runner
 * 
 * Tests the integration between PipelineScriptRunner and the service system.
 */
class PipelineScriptRunnerTest : StringSpec({
    
    afterSpec {
        // Clean up Koin context after all tests complete
        try { stopKoin() } catch (_: Exception) { /* already stopped */ }
    }
    
    "should execute with valid script file" {
        // Create a temporary script file
        val tempScript = Files.createTempFile("test-pipeline", ".pipeline.kts")
        Files.write(tempScript, "println(\"Hello from pipeline script\")".toByteArray())
        
        try {
            val result = PipelineScriptRunner.execute(
                scriptPath = tempScript.toString(),
                configPath = ""
            )
            
            result shouldNotBe null
            result.shouldBeInstanceOf<dev.rubentxu.pipeline.runner.PipelineResult>()
            result.status shouldBe Status.SUCCESS
            result.errors.isEmpty() shouldBe true
            
            // Verify that real script evaluation occurred
            val hasRealExecution = result.logs.any { it.contains("Pipeline script evaluated successfully with REAL ScriptEngine") }
            hasRealExecution shouldBe true
            
        } finally {
            Files.deleteIfExists(tempScript)
        }
    }
    
    "should fail with non-existent script file" {
        val result = PipelineScriptRunner.execute(
            scriptPath = "/path/to/nonexistent/script.pipeline.kts",
            configPath = ""
        )
        
        result shouldNotBe null
        result.status shouldBe Status.FAILURE
        result.errors.isNotEmpty() shouldBe true
        result.errors.any { it.contains("Script file does not exist") } shouldBe true
    }
    
    "should handle custom working directory" {
        // Create a temporary script file
        val tempScript = Files.createTempFile("test-pipeline", ".pipeline.kts")
        Files.write(tempScript, "println(\"Hello from pipeline script\")".toByteArray())
        
        val customWorkingDir = System.getProperty("java.io.tmpdir")
        
        try {
            val result = PipelineScriptRunner.execute(
                scriptPath = tempScript.toString(),
                configPath = "",
                workingDirectory = customWorkingDir
            )
            
            result shouldNotBe null
            result.status shouldBe Status.SUCCESS
            
            // Verify real script execution occurred with custom working directory
            val hasRealExecution = result.logs.any { it.contains("Pipeline script evaluated successfully with REAL ScriptEngine") }
            hasRealExecution shouldBe true
            
        } finally {
            Files.deleteIfExists(tempScript)
        }
    }
    
    "should provide legacy compatibility via evalWithScriptEngineManager" {
        // Create a temporary script file
        val tempScript = Files.createTempFile("test-pipeline", ".pipeline.kts")
        Files.write(tempScript, "println(\"Hello from pipeline script\")".toByteArray())
        
        try {
            val result = PipelineScriptRunner.evalWithScriptEngineManager(
                scriptPath = tempScript.toString(),
                configPath = ""
            )
            
            result shouldNotBe null
            result.shouldBeInstanceOf<dev.rubentxu.pipeline.runner.PipelineResult>()
            result.status shouldBe Status.SUCCESS
            
            // Verify real script execution via legacy method
            val hasRealExecution = result.logs.any { it.contains("Pipeline script evaluated successfully with REAL ScriptEngine") }
            hasRealExecution shouldBe true
            
        } finally {
            Files.deleteIfExists(tempScript)
        }
    }
    
    "should handle service initialization correctly" {
        // Create a temporary script file
        val tempScript = Files.createTempFile("test-pipeline", ".pipeline.kts")
        Files.write(tempScript, "println(\"Hello from pipeline script\")".toByteArray())
        
        try {
            val result = PipelineScriptRunner.execute(
                scriptPath = tempScript.toString(),
                configPath = ""
            )
            
            // Verify that the execution completed successfully, which means
            // service initialization worked correctly
            result shouldNotBe null
            result.status shouldBe Status.SUCCESS
            
            // Verify that logs contain service initialization information
            result.logs.isNotEmpty() shouldBe true
            
            // Verify real script execution occurred
            val hasRealExecution = result.logs.any { it.contains("Pipeline script evaluated successfully with REAL ScriptEngine") }
            hasRealExecution shouldBe true
            
        } finally {
            Files.deleteIfExists(tempScript)
        }
    }
    
    "complete integration flow - script creation to execution" {
        // Create a realistic pipeline script
        val tempScript = Files.createTempFile("integration-pipeline", ".pipeline.kts")
        val scriptContent = """
            // Integration test pipeline script
            println("=== Pipeline Integration Test ===")
            println("Script executing in service-oriented architecture")
            println("Service integration active")
            
            // Simulate some pipeline operations
            val buildNumber = System.currentTimeMillis()
            println("Build number: ${'$'}buildNumber")
            
            println("Integration test script completed")
        """.trimIndent()
        
        Files.write(tempScript, scriptContent.toByteArray())
        
        try {
            // Execute the integration test
            val result = PipelineScriptRunner.execute(
                scriptPath = tempScript.toString(),
                configPath = "integration-config.yaml"
            )
            
            // Verify results
            result shouldNotBe null
            result.status shouldBe Status.SUCCESS
            result.errors.isEmpty() shouldBe true
            result.stageResults.isNotEmpty() shouldBe true
            
            // Verify real script execution occurred
            val hasRealExecution = result.logs.any { it.contains("Pipeline script evaluated successfully with REAL ScriptEngine") }
            hasRealExecution shouldBe true
            
        } finally {
            Files.deleteIfExists(tempScript)
        }
    }
})
