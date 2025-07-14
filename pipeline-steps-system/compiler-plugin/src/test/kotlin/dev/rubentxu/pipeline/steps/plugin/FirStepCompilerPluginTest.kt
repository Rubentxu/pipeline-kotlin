package dev.rubentxu.pipeline.steps.plugin

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Tests for the FIR part of the @Step compiler plugin.
 *
 * These tests validate the K2/FIR logic for context injection and validation,
 * ensuring a pure frontend-based plugin behavior.
 */
class FirStepCompilerPluginTest {

    @Test
    fun `step function should have implicit PipelineContext receiver`() {
        val source = SourceFile.kotlin("main.kt", """
            import dev.rubentxu.pipeline.steps.annotations.Step
            import dev.rubentxu.pipeline.context.PipelineContext

            // This function should have PipelineContext implicitly available
            @Step
            fun mySimpleStep() {
                // If the plugin works, this line should compile successfully
                // because 'pipeline' is a property of the implicit PipelineContext.
                println(pipeline.name)
            }
        """)

        val compilation = KotlinCompilation().apply {
            sources = listOf(source)
            compilerPlugins = listOf(StepCompilerPluginRegistrar())
            inheritClassPath = true
            supportsK2 = true
            verbose = false
        }

        val result = compilation.compile()

        // This test will initially fail with an "Unresolved reference: pipeline" error.
        // The goal of the refactoring is to make it pass.
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "Compilation should succeed. Messages: ${result.messages}")
    }

    @Test
    fun `compilation should fail if PipelineContext is a manual parameter`() {
        val source = SourceFile.kotlin("main.kt", """
            import dev.rubentxu.pipeline.steps.annotations.Step
            import dev.rubentxu.pipeline.context.PipelineContext

            @Step
            fun myInvalidStep(context: PipelineContext) {
                println("This should not compile.")
            }
        """)

        val compilation = KotlinCompilation().apply {
            sources = listOf(source)
            compilerPlugins = listOf(StepCompilerPluginRegistrar())
            inheritClassPath = true
            supportsK2 = true
            verbose = false
        }

        val result = compilation.compile()

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        // We will later assert the specific error message.
    }
}
