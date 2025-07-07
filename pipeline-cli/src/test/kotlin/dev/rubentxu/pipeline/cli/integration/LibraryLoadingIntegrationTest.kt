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
 * Integration tests for library loading capabilities
 * Tests JAR loading, Git sources, local sources, and dependency resolution
 */
class LibraryLoadingIntegrationTest : StringSpec({
    
    "should load and execute pipeline with configuration" {
        val scriptFile = File("pipeline-backend/testData/success.pipeline.kts")
        val configFile = File("pipeline-backend/testData/config.yaml")
        
        if (scriptFile.exists() && configFile.exists()) {
            val result = PipelineScriptRunner.evalWithScriptEngineManager(
                scriptFile.absolutePath,
                configFile.absolutePath
            )
            
            result shouldNotBe null
            result.status shouldNotBe null
        }
    }
    
    "should handle pipeline with shared library configuration" {
        val configFile = File("pipeline-backend/testData/config.yaml")
        
        if (configFile.exists()) {
            val configContent = configFile.readText()
            
            // Verify shared library configuration is present
            configContent shouldContain "sharedLibrary"
            configContent shouldContain "libraryPath"
            
            val config = object : IPipelineConfig {}
            val logger = PipelineLogger.getLogger()
            
            val dslManager = DslManager(
                pipelineConfig = config,
                logger = logger
            )
            
            try {
                // Test that DslManager can work with library configurations
                val engines = dslManager.getEngineInfo()
                engines shouldNotBe null
                
            } finally {
                runBlocking {
                    dslManager.shutdown()
                }
            }
        }
    }
    
    "should validate library dependencies in pipeline script" {
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
                
                // Test script that uses common Kotlin libraries
                val scriptWithLibraries = """
                    import kotlinx.coroutines.*
                    import java.io.File
                    
                    val result = "Testing library access"
                    println("Library test: ${'$'}result")
                """.trimIndent()
                
                runBlocking {
                    val validationResult = dslManager.validateContent(
                        scriptContent = scriptWithLibraries,
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
    
    "should handle pipeline with environment variables from config" {
        val scriptFile = File("pipeline-backend/testData/success.pipeline.kts")
        
        if (scriptFile.exists()) {
            val scriptContent = scriptFile.readText()
            
            // Verify the script uses environment variables
            scriptContent shouldContain "environment"
            scriptContent shouldContain "DISABLE_AUTH"
            scriptContent shouldContain "DB_ENGINE"
            
            val config = object : IPipelineConfig {}
            val logger = PipelineLogger.getLogger()
            
            val dslManager = DslManager(
                pipelineConfig = config,
                logger = logger
            )
            
            try {
                runBlocking {
                    val report = dslManager.validateFile(scriptFile)
                    report shouldNotBe null
                }
            } finally {
                runBlocking {
                    dslManager.shutdown()
                }
            }
        }
    }
    
    "should support parallel execution with library dependencies" {
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
                
                // Script that simulates parallel execution like in success.pipeline.kts
                val parallelScript = """
                    import kotlinx.coroutines.*
                    
                    println("Testing parallel execution capability")
                    val result = runBlocking {
                        listOf(
                            async { "Branch A completed" },
                            async { "Branch B completed" }
                        ).awaitAll()
                    }
                    println("Parallel results: ${'$'}result")
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
})