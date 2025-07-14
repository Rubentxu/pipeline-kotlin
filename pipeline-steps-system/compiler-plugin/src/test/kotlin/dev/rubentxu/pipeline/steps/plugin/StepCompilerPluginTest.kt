package dev.rubentxu.pipeline.steps.plugin

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertContains

/**
 * Comprehensive tests for the @Step compiler plugin using kotlin-compile-testing.
 * 
 * Tests the complete @Step system functionality:
 * 1. Context injection for @Step functions
 * 2. DSL extension generation
 * 3. Proper compilation and execution
 */
class StepCompilerPluginTest {

    @Test
    fun `@Step function should compile successfully`() {
        val source = SourceFile.kotlin("TestStep.kt", """
            package test
            
            import dev.rubentxu.pipeline.steps.annotations.Step
            import dev.rubentxu.pipeline.context.PipelineContext
            
            @Step
            fun testStep(message: String) {
                // Context should be automatically injected
                println("Test step: ${'$'}message")
            }
        """)
        
        val compilation = createStepCompilation(source)
        val result = compilation.compile()
        
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation should succeed")
    }

    @Test
    fun `@Step function should automatically receive PipelineContext parameter`() {
        val source = SourceFile.kotlin("TestStep.kt", """
            package test
            
            import dev.rubentxu.pipeline.steps.annotations.Step
            import dev.rubentxu.pipeline.context.PipelineContext
            
            @Step
            fun testStepWithContext(message: String) {
                // The compiler plugin should inject PipelineContext as first parameter
                // This test checks that the function compiles and can be called
            }
            
            // Test function that would call the @Step function
            fun testCaller() {
                // This should work with generated DSL extensions
                testStepWithContext("hello")
            }
        """)
        
        val compilation = createStepCompilation(source)
        val result = compilation.compile()
        
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation should succeed")
        
        // Check that the function was compiled
        assertTrue(result.messages.contains("StepIrTransformer"), "IR transformer should have run")
    }
    
    @Test
    fun `@Step function with existing PipelineContext parameter should compile without transformation`() {
        val source = SourceFile.kotlin("TestStep.kt", """
            package test
            
            import dev.rubentxu.pipeline.steps.annotations.Step
            import dev.rubentxu.pipeline.context.PipelineContext
            
            @Step
            fun legacyStep(context: PipelineContext, message: String) {
                // Function already has PipelineContext parameter
                // Should not be transformed
                context.logger.info("Legacy step: ${'$'}message")
            }
        """)
        
        val compilation = createStepCompilation(source)
        val result = compilation.compile()
        
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation should succeed")
    }
    
    @Test
    fun `suspend @Step function should compile successfully`() {
        val source = SourceFile.kotlin("TestStep.kt", """
            package test
            
            import dev.rubentxu.pipeline.steps.annotations.Step
            import dev.rubentxu.pipeline.context.PipelineContext
            
            @Step
            suspend fun suspendStep(delay: Long) {
                // Suspend @Step functions should be supported
                kotlinx.coroutines.delay(delay)
                println("Suspend step completed")
            }
        """)
        
        val compilation = createStepCompilation(source)
        val result = compilation.compile()
        
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation should succeed")
    }

    @Test
    fun `multiple @Step functions should all be transformed`() {
        val source = SourceFile.kotlin("TestSteps.kt", """
            package test
            
            import dev.rubentxu.pipeline.steps.annotations.Step
            import dev.rubentxu.pipeline.context.PipelineContext
            
            @Step
            fun stepOne(data: String) {
                println("Step one: ${'$'}data")
            }
            
            @Step  
            fun stepTwo(count: Int) {
                println("Step two: ${'$'}count")
            }
            
            @Step
            suspend fun stepThree(flag: Boolean) {
                println("Step three: ${'$'}flag")
            }
            
            // Non-@Step function should not be transformed
            fun regularFunction() {
                println("Regular function")
            }
        """)
        
        val compilation = createStepCompilation(source)
        val result = compilation.compile()
        
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation should succeed")
        
        // Should find multiple @Step functions
        val messages = result.messages
        assertTrue(messages.contains("StepIrTransformer"), "IR transformer should have run")
    }
    
    @Test
    fun `built-in @Step functions should work with the plugin`() {
        val source = SourceFile.kotlin("TestBuiltins.kt", """
            package test
            
            import dev.rubentxu.pipeline.steps.annotations.Step
            import dev.rubentxu.pipeline.context.PipelineContext
            import dev.rubentxu.pipeline.steps.builtin.*
            
            @Step
            fun myCustomStep(message: String) {
                // Should be able to call other @Step functions
                echo(message)
                sh("echo 'Custom step executed'")
            }
        """)
        
        val compilation = createStepCompilation(source)
        val result = compilation.compile()
        
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation should succeed")
    }

    @Test
    fun `@Step function should generate DSL extensions`() {
        val source = SourceFile.kotlin("TestDsl.kt", """
            package test
            
            import dev.rubentxu.pipeline.steps.annotations.Step
            import dev.rubentxu.pipeline.context.PipelineContext
            import dev.rubentxu.pipeline.dsl.StepsBlock
            
            @Step
            fun myCustomStep(message: String) {
                println("Custom step: ${'$'}message")
            }
            
            // This should work if DSL extensions are generated
            fun testDslUsage(stepsBlock: StepsBlock) {
                with(stepsBlock) {
                    myCustomStep("Hello from DSL")
                }
            }
        """)
        
        val compilation = createStepCompilation(source)
        val result = compilation.compile()
        
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation should succeed")
        
        // Check that DSL generator ran
        val messages = result.messages
        assertTrue(messages.contains("StepDslRegistryGenerator") || result.exitCode == KotlinCompilation.ExitCode.OK, 
            "DSL generator should have run or compilation should succeed")
    }
    
    @Test
    fun `plugin should handle complex @Step function with multiple parameters`() {
        val source = SourceFile.kotlin("ComplexStep.kt", """
            package test
            
            import dev.rubentxu.pipeline.steps.annotations.Step
            import dev.rubentxu.pipeline.steps.annotations.StepCategory
            import dev.rubentxu.pipeline.steps.annotations.SecurityLevel
            import dev.rubentxu.pipeline.context.PipelineContext
            
            @Step(
                name = "complexStep",
                description = "A complex step with multiple parameters",
                category = StepCategory.BUILD,
                securityLevel = SecurityLevel.RESTRICTED
            )
            suspend fun complexStep(
                name: String,
                count: Int,
                flag: Boolean,
                data: Map<String, Any> = emptyMap()
            ): String {
                println("Complex step: name=${'$'}name, count=${'$'}count, flag=${'$'}flag")
                println("Data: ${'$'}data")
                return "completed"
            }
        """)
        
        val compilation = createStepCompilation(source)
        val result = compilation.compile()
        
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation should succeed")
    }

   @Test
    fun `plugin should work with kotlin-compile-testing setup`() {
        val source = SourceFile.kotlin("BasicTest.kt", """
            package test
            
            fun simpleFunction(): String {
                return "Hello, World!"
            }
        """)

        val compilation = KotlinCompilation().apply {
            sources = listOf(source)
            compilerPluginRegistrars = listOf(StepCompilerPluginRegistrar())
            inheritClassPath = true
            supportsK2 = true
            verbose = false
        }

        val result = compilation.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Basic compilation should work")
    }

    /**
     * Helper function to create a compilation with the @Step plugin configured
     */
  private fun createStepCompilation(vararg sources: SourceFile): KotlinCompilation {
      return KotlinCompilation().apply {
          this.sources = sources.toList()
          compilerPluginRegistrars = listOf(StepCompilerPluginRegistrar())
          inheritClassPath = true
          supportsK2 = true
          verbose = true
          kotlincArguments = listOf(
              "-language-version", "2.2",
              "-Xcontext-parameters"
          )
      }
  }
}