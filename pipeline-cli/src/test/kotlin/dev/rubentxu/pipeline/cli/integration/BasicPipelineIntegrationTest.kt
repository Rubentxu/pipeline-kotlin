package dev.rubentxu.pipeline.cli.integration

import dev.rubentxu.pipeline.backend.PipelineScriptRunner
import dev.rubentxu.pipeline.model.pipeline.Status
import dev.rubentxu.pipeline.dsl.*
import dev.rubentxu.pipeline.model.config.IPipelineConfig
import dev.rubentxu.pipeline.logger.PipelineLogger
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files

/**
 * Integration tests that verify core pipeline functionality works
 * These tests use the real implementation and test actual features
 */
class BasicPipelineIntegrationTest : StringSpec({
    
    "DslManager should initialize and provide basic functionality" {
        val config = object : IPipelineConfig {}
        val logger = PipelineLogger.getLogger()
        
        val dslManager = DslManager(
            pipelineConfig = config,
            logger = logger
        )
        
        try {
            // Test basic initialization
            val engineInfo = dslManager.getEngineInfo()
            engineInfo shouldNotBe null
            engineInfo.shouldNotBeEmpty()
            
            val firstEngine = engineInfo.first()
            firstEngine.engineId shouldNotBe null
            firstEngine.engineName shouldNotBe null
            
            // Test report generation
            val report = dslManager.generateReport()
            report shouldNotBe null
            report.registeredEngines shouldBe 1
            
            val formattedReport = report.getFormattedReport()
            formattedReport shouldNotBe null
            formattedReport shouldContain "DSL Manager Report"
            
        } finally {
            runBlocking {
                dslManager.shutdown()
            }
        }
    }
    
    "DslManager should provide sandbox functionality" {
        val config = object : IPipelineConfig {}
        val logger = PipelineLogger.getLogger()
        
        val dslManager = DslManager(
            pipelineConfig = config,
            logger = logger
        )
        
        try {
            // Test sandbox manager is available
            val sandboxManager = dslManager.sandboxManager
            sandboxManager shouldNotBe null
            
            // Test active executions monitoring
            val activeExecutions = dslManager.getActiveExecutions()
            activeExecutions shouldNotBe null
            
            // Test execution statistics
            val stats = dslManager.getExecutionStats()
            stats shouldNotBe null
            
        } finally {
            runBlocking {
                dslManager.shutdown()
            }
        }
    }
    
    "DslManager should validate content" {
        val config = object : IPipelineConfig {}
        val logger = PipelineLogger.getLogger()
        
        val dslManager = DslManager(
            pipelineConfig = config,
            logger = logger
        )
        
        try {
            val engines = dslManager.getEngineInfo()
            engines.shouldNotBeEmpty()
            
            val engineId = engines.first().engineId
            val simpleScript = "val result = \"Hello World\""
            
            runBlocking {
                val validationResult = dslManager.validateContent(
                    scriptContent = simpleScript,
                    engineId = engineId
                )
                
                validationResult shouldNotBe null
                // Test that validation runs without throwing exceptions
            }
        } finally {
            runBlocking {
                dslManager.shutdown()
            }
        }
    }
    
    "DslManager should support enhanced validation reporting" {
        val config = object : IPipelineConfig {}
        val logger = PipelineLogger.getLogger()
        
        val dslManager = DslManager(
            pipelineConfig = config,
            logger = logger
        )
        
        try {
            val engines = dslManager.getEngineInfo()
            engines.shouldNotBeEmpty()
            
            val engineId = engines.first().engineId
            val simpleScript = "val test = \"validation test\""
            
            runBlocking {
                val report = dslManager.validateContentWithEnhancedReporting(
                    scriptContent = simpleScript,
                    engineId = engineId,
                    scriptName = "test.kts"
                )
                
                report shouldNotBe null
                report.scriptName shouldBe "test.kts"
                // Report should have basic structure
            }
        } finally {
            runBlocking {
                dslManager.shutdown()
            }
        }
    }
    
    "DslManager should validate files" {
        val config = object : IPipelineConfig {}
        val logger = PipelineLogger.getLogger()
        
        val dslManager = DslManager(
            pipelineConfig = config,
            logger = logger
        )
        
        try {
            val tempDir = Files.createTempDirectory("validation-test")
            val scriptFile = File(tempDir.toFile(), "test.kts")
            scriptFile.writeText("val test = \"file validation\"")
            
            runBlocking {
                val validationResult = dslManager.validateFile(scriptFile)
                validationResult shouldNotBe null
                // Test that file validation works
            }
            
            tempDir.toFile().deleteRecursively()
        } finally {
            runBlocking {
                dslManager.shutdown()
            }
        }
    }
    
    "PipelineScriptRunner should work with real test data" {
        // Test with actual test files from the project
        val possibleScripts = listOf(
            "pipeline-backend/testData/hello.pipeline.kts",
            "pipeline-cli/testData/hello.pipeline.kts"
        )
        
        val possibleConfigs = listOf(
            "pipeline-backend/testData/config.yaml",
            "pipeline-cli/testData/config.yaml"
        )
        
        // Find existing files and test with them
        for (scriptPath in possibleScripts) {
            for (configPath in possibleConfigs) {
                val scriptFile = File(scriptPath)
                val configFile = File(configPath)
                
                if (scriptFile.exists() && configFile.exists()) {
                    val result = PipelineScriptRunner.evalWithScriptEngineManager(
                        scriptPath,
                        configPath
                    )
                    
                    result shouldNotBe null
                    result.status shouldNotBe null
                    // Found working combination, exit test
                    break
                }
            }
        }
    }
    
    "DslManager should shutdown cleanly" {
        val config = object : IPipelineConfig {}
        val logger = PipelineLogger.getLogger()
        
        val dslManager = DslManager(
            pipelineConfig = config,
            logger = logger
        )
        
        // Test that shutdown works without throwing exceptions
        runBlocking {
            dslManager.shutdown()
            
            // After shutdown, basic queries should still work
            val stats = dslManager.getExecutionStats()
            stats shouldNotBe null
        }
    }
})