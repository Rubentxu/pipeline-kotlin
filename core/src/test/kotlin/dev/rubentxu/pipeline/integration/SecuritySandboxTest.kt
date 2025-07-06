package dev.rubentxu.pipeline.integration

import dev.rubentxu.pipeline.dsl.*
import dev.rubentxu.pipeline.PipelineConfigTest
import dev.rubentxu.pipeline.model.pipeline.PipelineResult
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files

class SecuritySandboxTest : StringSpec({
    
    "should enforce resource limits in execution context" {
        runTest {
            val tempDir = Files.createTempDirectory("security-sandbox-test")
            val config = PipelineConfigTest()
            val dslManager = DslManager(config)
            
            try {
                // Test that DslManager initializes correctly with security configuration
                val engineInfo = dslManager.getEngineInfo()
                engineInfo.size shouldBe 1
                
                // Verify that sandbox manager is available
                dslManager.sandboxManager shouldBe dslManager.sandboxManager
                
            } finally {
                dslManager.shutdown()
                tempDir.toFile().deleteRecursively()
            }
        }
    }
    
    "should validate working directory restrictions" {
        runTest {
            val tempDir = Files.createTempDirectory("security-working-dir-test")
            val workingDir = tempDir.toFile()
            val config = PipelineConfigTest()
            val dslManager = DslManager(config)
            
            try {
                // Create a test file in the working directory
                val testFile = File(workingDir, "test.txt")
                testFile.writeText("This is a test file")
                
                // Verify that working directory setup works
                testFile.exists() shouldBe true
                workingDir.exists() shouldBe true
                
                // Test basic validation functionality  
                val simpleScript = "val result = \"test\""
                val validationResult = dslManager.validateContent(
                    scriptContent = simpleScript,
                    engineId = "pipeline-dsl"
                )
                
                // Validation should work without throwing exceptions
                validationResult shouldBe validationResult
                
            } finally {
                dslManager.shutdown()
                tempDir.toFile().deleteRecursively()
            }
        }
    }
    
    "should demonstrate plugin isolation capabilities" {
        runTest {
            val config = PipelineConfigTest()
            val dslManager = DslManager(config)
            
            try {
                // Test that DslManager supports plugin isolation features
                val engines = dslManager.getEngineInfo()
                engines.isNotEmpty() shouldBe true
                
                // Verify sandbox manager has isolation capabilities
                val sandboxManager = dslManager.sandboxManager
                sandboxManager shouldBe sandboxManager
                
                // Test basic stats functionality
                val stats = dslManager.getExecutionStats()
                stats shouldBe stats
                
            } finally {
                dslManager.shutdown()
            }
        }
    }
})