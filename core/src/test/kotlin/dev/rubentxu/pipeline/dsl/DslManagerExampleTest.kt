package dev.rubentxu.pipeline.dsl

import dev.rubentxu.pipeline.model.config.IPipelineConfig
import dev.rubentxu.pipeline.logger.IPipelineLogger
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.mockk.*
import kotlinx.coroutines.runBlocking

class DslManagerExampleTest : StringSpec({
    
    "should create DslManager and get basic info" {
        val mockConfig = mockk<IPipelineConfig>(relaxed = true)
        val mockLogger = mockk<IPipelineLogger>(relaxed = true)
        
        // Test that we can create the DslManager without exception
        val dslManager = try {
            DslManager(
                pipelineConfig = mockConfig,
                logger = mockLogger
            )
        } catch (e: Exception) {
            throw AssertionError("Failed to create DslManager: ${e.message}", e)
        }
        
        try {
            // Test basic functionality
            val engineInfo = dslManager.getEngineInfo()
            engineInfo.shouldNotBeEmpty()
            engineInfo.size shouldBe 1 // Should have pipeline engine
            
            val firstEngine = engineInfo.first()
            firstEngine.engineId shouldNotBe null
            firstEngine.engineName shouldNotBe null
            
            // Test report generation
            val report = dslManager.generateReport()
            report shouldNotBe null
            report.registeredEngines shouldBe 1
            
        } finally {
            runBlocking {
                dslManager.shutdown()
            }
        }
    }
    
    "should handle validation operations" {
        val mockConfig = mockk<IPipelineConfig>(relaxed = true)
        val mockLogger = mockk<IPipelineLogger>(relaxed = true)
        
        val dslManager = DslManager(
            pipelineConfig = mockConfig,
            logger = mockLogger
        )
        
        try {
            val engines = dslManager.getEngineInfo()
            if (engines.isNotEmpty()) {
                val engineId = engines.first().engineId
                
                runBlocking {
                    val validationResult = dslManager.validateContent(
                        scriptContent = "val test = \"Hello World\"",
                        engineId = engineId
                    )
                    validationResult shouldNotBe null
                }
            }
        } finally {
            runBlocking {
                dslManager.shutdown()
            }
        }
    }
    
    "should provide execution statistics" {
        val mockConfig = mockk<IPipelineConfig>(relaxed = true)
        val mockLogger = mockk<IPipelineLogger>(relaxed = true)
        
        val dslManager = DslManager(
            pipelineConfig = mockConfig,
            logger = mockLogger
        )
        
        try {
            val stats = dslManager.getExecutionStats()
            stats shouldNotBe null
            
            val activeExecutions = dslManager.getActiveExecutions()
            activeExecutions shouldNotBe null
            
        } finally {
            runBlocking {
                dslManager.shutdown()
            }
        }
    }
})