package dev.rubentxu.pipeline.backend.execution

import dev.rubentxu.pipeline.backend.execution.impl.DefaultScriptEvaluator
import dev.rubentxu.pipeline.backend.execution.impl.DefaultConfigurationLoader
import dev.rubentxu.pipeline.model.pipeline.PipelineResult
import dev.rubentxu.pipeline.model.pipeline.Status
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File
import java.nio.file.Path

class PipelineOrchestratorTest : StringSpec({

    "should evaluate script successfully" {
        val scriptEvaluator = DefaultScriptEvaluator()
        val scriptPath = Path.of("testData/success.pipeline.kts")
        
        val result = scriptEvaluator.evaluate(scriptPath)
        
        result.isSuccess shouldBe true
        result.getOrNull() shouldNotBe null
    }

    "should load configuration successfully" {
        val configLoader = DefaultConfigurationLoader()
        val configPath = Path.of("testData/config.yaml")
        
        val result = configLoader.load(configPath)
        
        result.isSuccess shouldBe true
        result.getOrNull() shouldNotBe null
    }

    "should orchestrate pipeline execution successfully" {
        val orchestrator = PipelineOrchestrator()
        val context = ExecutionContext(System.getenv())
        
        val result = orchestrator.execute(
            scriptPath = Path.of("testData/success.pipeline.kts"),
            configPath = Path.of("testData/config.yaml"),
            context = context
        )
        
        result.isSuccess shouldBe true
        val pipelineResult = result.getOrNull()
        pipelineResult shouldNotBe null
        pipelineResult?.status shouldBe Status.SUCCESS
    }
})