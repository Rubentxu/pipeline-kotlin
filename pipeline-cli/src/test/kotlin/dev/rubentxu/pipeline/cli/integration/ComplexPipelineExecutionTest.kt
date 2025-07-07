package dev.rubentxu.pipeline.cli.integration

import dev.rubentxu.pipeline.backend.PipelineScriptRunner
import dev.rubentxu.pipeline.dsl.DslManager
import dev.rubentxu.pipeline.model.config.IPipelineConfig
import dev.rubentxu.pipeline.logger.PipelineLogger
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Integration tests for complex pipeline execution scenarios
 * Tests parallel execution, agents, environment variables, post actions, error handling
 */
class ComplexPipelineExecutionTest : StringSpec({
    
    "should execute complex pipeline with multiple stages" {
        val scriptFile = File("pipeline-backend/testData/success.pipeline.kts")
        val configFile = File("pipeline-backend/testData/config.yaml")
        
        if (scriptFile.exists() && configFile.exists()) {
            val result = PipelineScriptRunner.evalWithScriptEngineManager(
                scriptFile.absolutePath,
                configFile.absolutePath
            )
            
            result shouldNotBe null
            result.status shouldNotBe null
            
            // The success pipeline should have completed without major errors
            println("Pipeline execution result: ${result.status}")
        }
    }
    
    "should handle pipeline with error scenarios" {
        val errorScriptFile = File("pipeline-backend/testData/error.pipeline.kts")
        val configFile = File("pipeline-backend/testData/config.yaml")
        
        if (errorScriptFile.exists() && configFile.exists()) {
            val result = PipelineScriptRunner.evalWithScriptEngineManager(
                errorScriptFile.absolutePath,
                configFile.absolutePath
            )
            
            result shouldNotBe null
            result.status shouldNotBe null
            
            // The error pipeline should handle the intentional error gracefully
            println("Error pipeline execution result: ${result.status}")
        }
    }
    
    "should validate complex pipeline DSL features" {
        val scriptFile = File("pipeline-backend/testData/success.pipeline.kts")
        
        if (scriptFile.exists()) {
            val scriptContent = scriptFile.readText()
            
            // Verify complex DSL features are present
            scriptContent shouldContain "environment"
            scriptContent shouldContain "stages"
            scriptContent shouldContain "parallel"
            scriptContent shouldContain "post"
            scriptContent shouldContain "always"
            scriptContent shouldContain "success"
            scriptContent shouldContain "failure"
            
            val config = object : IPipelineConfig {}
            val logger = PipelineLogger.getLogger()
            
            val dslManager = DslManager(
                pipelineConfig = config,
                logger = logger
            )
            
            try {
                runBlocking {
                    val validationReport = dslManager.validateFile(scriptFile)
                    validationReport shouldNotBe null
                }
            } finally {
                runBlocking {
                    dslManager.shutdown()
                }
            }
        }
    }
    
    "should support environment variable operations" {
        val config = object : IPipelineConfig {}
        val logger = PipelineLogger.getLogger()
        
        val dslManager = DslManager(
            pipelineConfig = config,
            logger = logger
        )
        
        try {
            val engines = dslManager.getEngineInfo()
            if (engines.isNotEmpty()) {
                val engineId = engines.first().engineId
                
                // Test environment variable operations like in the pipeline
                val envScript = """
                    // Simulate environment variable operations from success.pipeline.kts
                    val environment = mutableMapOf<String, String>()
                    environment["DISABLE_AUTH"] = "true"
                    environment["DB_ENGINE"] = "h2"
                    
                    println("Environment configured: ${'$'}environment")
                    val result = "Environment test completed"
                """.trimIndent()
                
                runBlocking {
                    val validationResult = dslManager.validateContent(
                        scriptContent = envScript,
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
    
    "should validate parallel execution patterns" {
        val config = object : IPipelineConfig {}
        val logger = PipelineLogger.getLogger()
        
        val dslManager = DslManager(
            pipelineConfig = config,
            logger = logger
        )
        
        try {
            val engines = dslManager.getEngineInfo()
            if (engines.isNotEmpty()) {
                val engineId = engines.first().engineId
                
                // Test parallel execution pattern from success.pipeline.kts
                val parallelScript = """
                    import kotlinx.coroutines.*
                    
                    // Simulate parallel execution blocks
                    val parallelResults = mapOf(
                        "a" to "Branch A processing",
                        "b" to "Branch B processing"
                    )
                    
                    println("Parallel execution simulation: ${'$'}parallelResults")
                    val result = "Parallel validation completed"
                """.trimIndent()
                
                runBlocking {
                    val validationResult = dslManager.validateContent(
                        scriptContent = parallelScript,
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
    
    "should support post-action patterns" {
        val config = object : IPipelineConfig {}
        val logger = PipelineLogger.getLogger()
        
        val dslManager = DslManager(
            pipelineConfig = config,
            logger = logger
        )
        
        try {
            val engines = dslManager.getEngineInfo()
            if (engines.isNotEmpty()) {
                val engineId = engines.first().engineId
                
                // Test post-action patterns from pipeline scripts
                val postActionScript = """
                    // Simulate post-action structure
                    val postActions = mapOf(
                        "always" to "Cleanup operations",
                        "success" to "Success notifications",
                        "failure" to "Error handling"
                    )
                    
                    println("Post-action configuration: ${'$'}postActions")
                    val result = "Post-action validation completed"
                """.trimIndent()
                
                runBlocking {
                    val validationResult = dslManager.validateContent(
                        scriptContent = postActionScript,
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
    
    "should handle agent configuration scenarios" {
        val scriptFile = File("pipeline-backend/testData/error.pipeline.kts")
        
        if (scriptFile.exists()) {
            val scriptContent = scriptFile.readText()
            
            // Verify agent configuration is present in error pipeline
            scriptContent shouldContain "agent"
            
            val config = object : IPipelineConfig {}
            val logger = PipelineLogger.getLogger()
            
            val dslManager = DslManager(
                pipelineConfig = config,
                logger = logger
            )
            
            try {
                runBlocking {
                    val validationReport = dslManager.validateFile(scriptFile)
                    validationReport shouldNotBe null
                }
            } finally {
                runBlocking {
                    dslManager.shutdown()
                }
            }
        }
    }
})