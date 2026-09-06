package dev.rubentxu.pipeline.v2.scripting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * The Kotlin host must expose the generated-artifact contract without making
 * a `.pipeline.kts` artifact depend on the application runtime module.
 */
@Timeout(30)
class CompiledScriptedEntryPointHostTest {

    @Test
    fun `compiled script returns a typed scripted entry point through default imports`() {
        val definition = ScriptDefinition.inline(
            text =
                """
                val source = ScriptedSourceLocation(
                    sourceId = ScriptedSourceId("entry.pipeline.kts"),
                    line = 12,
                    column = 7,
                )
                val entryPoint = object : CompiledScriptedEntryPoint {
                    override val artifact = ScriptedArtifactIdentity(
                        sourceDigest = "source-digest",
                        dslApiVersion = "dsl-v1",
                        compilerAdapterVersion = "compiler-v1",
                        runtimeCompatibilityVersion = "runtime-v1",
                        pluginLockDigest = "plugins-v1",
                        facadeSchemaDigest = "facade-v1",
                    )
                    override val entryPointId = "entry-point"

                    override suspend fun execute(steps: ScriptedStepFacade) {
                        steps.sh(
                            source.shellCallSite(),
                            "echo host-contract",
                            ReturnStatus,
                        )
                    }
                }
                entryPoint
                """.trimIndent(),
            classpath = listOfNotNull(ScriptDefinition.dslApiJar()),
        )

        val compilation = Kotlin24ScriptingHost().compile(definition)

        assertTrue(compilation.isSuccess, "Expected successful compilation: ${compilation.diagnostics}")
        assertNotNull(compilation.scriptInstance, "Expected the legacy script instance to remain available")
        val successfulCompilation = requireNotNull(compilation as? ScriptCompilationResult.Success) {
            "Expected a successful typed scripting result"
        }
        val entryPoint = requireNotNull(
            (successfulCompilation.output as? ScriptEvaluationOutput.CompiledEntryPoint)?.entryPoint,
        ) {
            "Expected a typed compiled scripted entry point"
        }

        assertEquals("entry-point", entryPoint.entryPointId)
        assertEquals("source-digest", entryPoint.artifact.sourceDigest)
    }
}
