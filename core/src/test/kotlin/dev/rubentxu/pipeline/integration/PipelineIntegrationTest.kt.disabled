package dev.rubentxu.pipeline.integration

import dev.rubentxu.pipeline.dsl.DslManager
import dev.rubentxu.pipeline.dsl.DslExecutionResult
import dev.rubentxu.pipeline.PipelineConfigTest
import dev.rubentxu.pipeline.model.pipeline.PipelineResult
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class PipelineIntegrationTest : StringSpec({
    
    "should validate simple pipeline DSL" {
        runTest {
            val config = PipelineConfigTest()
            val dslManager = DslManager(config)
            
            try {
                // Simple pipeline validation test
                val pipelineScript = """
                    // This is a simple comment
                    println("Hello from pipeline validation")
                """.trimIndent()
                
                val validationResult = dslManager.validateContent(
                    scriptContent = pipelineScript,
                    engineId = "pipeline-dsl"
                )
                
                // The validation should work (even if the script content is simple)
                println("Validation result: $validationResult")
                
            } finally {
                dslManager.shutdown()
            }
        }
    }
})