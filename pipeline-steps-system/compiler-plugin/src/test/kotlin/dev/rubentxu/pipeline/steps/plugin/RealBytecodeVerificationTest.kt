package dev.rubentxu.pipeline.steps.plugin

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import dev.rubentxu.pipeline.steps.plugin.RealKotlinCompilerTest.Companion.verifyNoCompilationErrors
import dev.rubentxu.pipeline.steps.plugin.RealKotlinCompilerTest.Companion.verifyPipelineContextInjection
import dev.rubentxu.pipeline.steps.plugin.RealKotlinCompilerTest.Companion.verifyMethodSignature
import dev.rubentxu.pipeline.steps.plugin.RealKotlinCompilerTest.Companion.verifyDslExtensionBytecode
import dev.rubentxu.pipeline.steps.plugin.RealKotlinCompilerTest.Companion.compareWithOriginal

/**
 * Tests específicos que verifican transformaciones REALES de bytecode.
 * Estos tests están diseñados para FALLAR inicialmente y demostrar que 
 * el plugin necesita implementar transformaciones reales de IR.
 */
class RealBytecodeVerificationTest {

    @Test
    fun `verify PipelineContext parameter injection in Step function bytecode`() {
        println("🎯 REAL BYTECODE VERIFICATION TEST: PipelineContext Injection")
        
        val stepSource = TestSources.stepFunction("processPayment", "amount: Double")
        val annotations = TestSources.annotationDefinitions()
        val context = TestSources.contextDefinitions()
        
        val result = RealKotlinCompilerTest.compile(
            sources = listOf(annotations, context, stepSource),
            enablePlugin = true
        )
        
        result.verifyNoCompilationErrors()
        
        // This test should FAIL initially because the plugin doesn't inject PipelineContext yet
        // When this test passes, it proves real bytecode transformation is working
        try {
            result.verifyPipelineContextInjection("processPayment")
            println("✅ TEST PASSED: PipelineContext injection working!")
        } catch (e: AssertionError) {
            println("❌ TEST FAILED AS EXPECTED: ${e.message}")
            println("💡 This failure proves we need to implement real IR transformation")
            // Re-throw to show the test failure
            throw e
        }
    }
    
    @Test
    fun `verify method signature transformation with PipelineContext`() {
        println("🎯 REAL BYTECODE VERIFICATION TEST: Method Signature Transformation")
        
        val stepSource = TestSources.stepFunction("validateUser", "email: String, password: String")
        val annotations = TestSources.annotationDefinitions()
        val context = TestSources.contextDefinitions()
        
        val result = RealKotlinCompilerTest.compile(
            sources = listOf(annotations, context, stepSource),
            enablePlugin = true
        )
        
        result.verifyNoCompilationErrors()
        
        // Original signature: (Ljava/lang/String;Ljava/lang/String;)V
        // Expected after transformation: (Ldev/rubentxu/pipeline/context/PipelineContext;Ljava/lang/String;Ljava/lang/String;)V
        val expectedSignature = "(Ldev/rubentxu/pipeline/context/PipelineContext;Ljava/lang/String;Ljava/lang/String;)V"
        
        try {
            result.verifyMethodSignature("validateUser", expectedSignature)
            println("✅ TEST PASSED: Method signature transformation working!")
        } catch (e: AssertionError) {
            println("❌ TEST FAILED AS EXPECTED: ${e.message}")
            println("💡 This failure proves the method signature is not being transformed")
            throw e
        }
    }
    
    @Test
    fun `verify suspend Step function transformation`() {
        println("🎯 REAL BYTECODE VERIFICATION TEST: Suspend Function Transformation")
        
        val suspendStepSource = TestSources.stepFunction("fetchUserData", "userId: String", isSuspend = true)
        val annotations = TestSources.annotationDefinitions()
        val context = TestSources.contextDefinitions()
        
        val result = RealKotlinCompilerTest.compile(
            sources = listOf(annotations, context, suspendStepSource),
            enablePlugin = true
        )
        
        result.verifyNoCompilationErrors()
        
        // Suspend functions have Continuation parameter, so expected signature should include both
        // PipelineContext AND Continuation
        val expectedSignature = "(Ldev/rubentxu/pipeline/context/PipelineContext;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"
        
        try {
            result.verifyMethodSignature("fetchUserData", expectedSignature)
            result.verifyPipelineContextInjection("fetchUserData")
            println("✅ TEST PASSED: Suspend function transformation working!")
        } catch (e: AssertionError) {
            println("❌ TEST FAILED AS EXPECTED: ${e.message}")
            println("💡 This failure proves suspend function transformation is not working")
            throw e
        }
    }
    
    @Test
    fun `verify DSL extension generation in bytecode`() {
        println("🎯 REAL BYTECODE VERIFICATION TEST: DSL Extension Generation")
        
        val stepSource = TestSources.stepFunction("deployApplication", "environment: String")
        val annotations = TestSources.annotationDefinitions()
        val context = TestSources.contextDefinitions()
        val stepsBlock = TestSources.stepsBlockDefinition()
        
        val result = RealKotlinCompilerTest.compile(
            sources = listOf(annotations, context, stepsBlock, stepSource),
            enablePlugin = true
        )
        
        result.verifyNoCompilationErrors()
        
        try {
            result.verifyDslExtensionBytecode("deployApplication")
            println("✅ TEST PASSED: DSL extension generation working!")
        } catch (e: AssertionError) {
            println("❌ TEST FAILED AS EXPECTED: ${e.message}")
            println("💡 This failure proves DSL extensions are not being generated in bytecode")
            throw e
        }
    }
    
    @Test
    fun `compare bytecode before and after plugin transformation`() {
        println("🎯 REAL BYTECODE VERIFICATION TEST: Before/After Comparison")
        
        val stepSource = TestSources.stepFunction("calculateTotal", "items: List<Item>")
        val annotations = TestSources.annotationDefinitions()
        val context = TestSources.contextDefinitions()
        
        // Compile WITHOUT plugin
        val originalResult = RealKotlinCompilerTest.compile(
            sources = listOf(annotations, context, stepSource),
            enablePlugin = false
        )
        
        // Compile WITH plugin
        val transformedResult = RealKotlinCompilerTest.compile(
            sources = listOf(annotations, context, stepSource),
            enablePlugin = true
        )
        
        originalResult.verifyNoCompilationErrors()
        transformedResult.verifyNoCompilationErrors()
        
        // Compare the two results
        val transformationReport = transformedResult.compareWithOriginal(originalResult, "calculateTotal")
        
        // If the plugin is working, we should see transformations
        if (transformationReport.hasTransformations()) {
            println("✅ TEST PASSED: Transformations detected in bytecode!")
            
            val methodTransformation = transformationReport.transformedMethods["calculateTotal"]
            if (methodTransformation != null) {
                assertTrue(methodTransformation.pipelineContextInjected, 
                    "PipelineContext should be injected")
                assertEquals(methodTransformation.originalParameterCount + 1, 
                    methodTransformation.newParameterCount,
                    "Parameter count should increase by 1")
            }
        } else {
            println("❌ TEST FAILED AS EXPECTED: No transformations detected")
            println("💡 This proves the plugin is not modifying bytecode yet")
            assertTrue(false, "No bytecode transformations detected. Plugin needs real implementation.")
        }
    }
    
    @Test
    fun `verify function with existing PipelineContext is not modified`() {
        println("🎯 REAL BYTECODE VERIFICATION TEST: Skip Already Transformed Functions")
        
        val stepWithContext = TestSources.stepFunction("alreadyTransformed", "data: String", hasContext = true)
        val annotations = TestSources.annotationDefinitions()
        val context = TestSources.contextDefinitions()
        
        // Compile WITHOUT plugin first
        val originalResult = RealKotlinCompilerTest.compile(
            sources = listOf(annotations, context, stepWithContext),
            enablePlugin = false
        )
        
        // Compile WITH plugin
        val transformedResult = RealKotlinCompilerTest.compile(
            sources = listOf(annotations, context, stepWithContext),
            enablePlugin = true
        )
        
        originalResult.verifyNoCompilationErrors()
        transformedResult.verifyNoCompilationErrors()
        
        // Compare - there should be NO transformations since PipelineContext already exists
        val transformationReport = transformedResult.compareWithOriginal(originalResult, "alreadyTransformed")
        
        // The method should NOT be transformed
        assertTrue(!transformationReport.hasTransformations() || 
                  (transformationReport.transformedMethods["alreadyTransformed"]?.pipelineContextInjected ?: true) == false,
                  "Function with existing PipelineContext should not be modified")
                  
        println("✅ Functions with existing PipelineContext are correctly skipped")
    }
    
    @Test 
    fun `verify complex Step function with multiple parameters`() {
        println("🎯 REAL BYTECODE VERIFICATION TEST: Complex Function Transformation")
        
        val complexStep = RealKotlinCompilerTest.SourceFile(
            "ComplexStep.kt",
            """
            package test
            
            import dev.rubentxu.pipeline.steps.annotations.Step
            import dev.rubentxu.pipeline.context.PipelineContext
            
            @Step(name = "processOrder", description = "Process complex order")
            suspend fun processComplexOrder(
                orderId: String,
                customerId: Long,
                items: List<String>,
                metadata: Map<String, Any> = emptyMap(),
                priority: Int = 1
            ): String {
                return "processed: " + orderId
            }
            """.trimIndent()
        )
        
        val annotations = TestSources.annotationDefinitions()
        val context = TestSources.contextDefinitions()
        
        val result = RealKotlinCompilerTest.compile(
            sources = listOf(annotations, context, complexStep),
            enablePlugin = true
        )
        
        result.verifyNoCompilationErrors()
        
        // Expected signature with PipelineContext as first parameter + Continuation for suspend
        val expectedSignature = "(Ldev/rubentxu/pipeline/context/PipelineContext;Ljava/lang/String;JLjava/util/List;Ljava/util/Map;ILkotlin/coroutines/Continuation;)Ljava/lang/Object;"
        
        try {
            result.verifyMethodSignature("processComplexOrder", expectedSignature)
            result.verifyPipelineContextInjection("processComplexOrder")
            println("✅ TEST PASSED: Complex function transformation working!")
        } catch (e: AssertionError) {
            println("❌ TEST FAILED AS EXPECTED: ${e.message}")
            println("💡 This failure proves complex function transformation is not working")
            throw e
        }
    }
}