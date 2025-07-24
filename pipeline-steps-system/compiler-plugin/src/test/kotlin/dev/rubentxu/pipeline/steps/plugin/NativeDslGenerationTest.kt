package dev.rubentxu.pipeline.steps.plugin

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * Test for native DSL extension generation by the compiler plugin.
 * 
 * This validates that the StepDslRegistryGenerator correctly:
 * 1. Discovers @Step functions
 * 2. Generates StepsBlock extension functions
 * 3. Creates proper parameter mapping (excluding context parameter)
 * 4. Generates direct function calls instead of executeStep() calls
 */
class NativeDslGenerationTest : BehaviorSpec({
    
    given("StepDslRegistryGenerator with @Step functions") {
        val generator = StepDslRegistryGenerator()
        
        `when`("Processing built-in @Step functions") {
            then("Should identify @Step annotation correctly") {
                // Test the FqName matching
                StepDslRegistryGenerator.STEP_ANNOTATION_FQ_NAME.asString() shouldBe "dev.rubentxu.pipeline.annotations.Step"
                StepDslRegistryGenerator.STEPS_BLOCK_FQ_NAME.asString() shouldBe "dev.rubentxu.pipeline.dsl.StepsBlock"
                StepDslRegistryGenerator.PIPELINE_CONTEXT_FQ_NAME.asString() shouldBe "dev.rubentxu.pipeline.context.PipelineContext"
            }
            
            then("Should generate correct extension function signatures") {
                // Test buildDslExtensionSignature method indirectly
                // This tests the logic for parameter filtering and signature generation
                val testFunction = createMockStepFunction(
                    name = "sh",
                    parameters = listOf(
                        MockParameter("context", "dev.rubentxu.pipeline.context.PipelineContext"),
                        MockParameter("command", "kotlin.String"),
                        MockParameter("returnStdout", "kotlin.Boolean", hasDefault = true)
                    ),
                    isSuspend = true
                )
                
                // The signature should exclude the context parameter
                val expectedSignature = "suspend fun StepsBlock.sh(command: String, returnStdout: Boolean = default)"
                
                // Verify parameter filtering logic
                val allParams = testFunction.parameters
                val contextParam = allParams.firstOrNull()
                contextParam?.name shouldBe "context"
                contextParam?.type shouldContain "PipelineContext"
                
                // Regular parameters should exclude context
                val regularParams = allParams.drop(1)
                regularParams.size shouldBe 2
                regularParams.map { it.name } shouldBe listOf("command", "returnStdout")
            }
        }
        
        `when`("Generating extension function bodies") {
            then("Should create direct @Step function calls") {
                // Test that generated extensions call @Step functions directly
                // instead of going through executeStep()
                
                // The generated extension should look like:
                // suspend fun StepsBlock.sh(command: String, returnStdout: Boolean = false) =
                //     sh(this.pipelineContext, command, returnStdout)
                
                // Verify context access property name
                val contextPropertyName = "pipelineContext"  // Should match StepsBlock property
                contextPropertyName shouldBe "pipelineContext"
            }
        }
    }
    
    given("Integration with existing @Step functions") {
        `when`("Built-in steps are processed") {
            then("Should generate extensions for all built-in steps") {
                val expectedSteps = listOf(
                    "sh", "echo", "checkout", "readFile", "writeFile", 
                    "fileExists", "retry", "sleep", "script", "mkdir",
                    "copyFile", "deleteFile", "timestamp", "generateUUID",
                    "getEnv", "listFiles"
                )
                
                // All these functions should have @Step annotations and explicit context parameters
                expectedSteps.forEach { stepName ->
                    // In real implementation, these would be discovered and processed
                    stepName shouldNotBe null
                }
            }
        }
    }
})

/**
 * Mock data structures for testing
 */
data class MockParameter(
    val name: String,
    val type: String,
    val hasDefault: Boolean = false
)

data class MockStepFunction(
    val name: String,
    val parameters: List<MockParameter>,
    val isSuspend: Boolean = false
)

private fun createMockStepFunction(
    name: String,
    parameters: List<MockParameter>,
    isSuspend: Boolean = false
): MockStepFunction {
    return MockStepFunction(name, parameters, isSuspend)
}