package dev.rubentxu.pipeline.v2.scripting

import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * The `script { }` block, as the COMPILER sees it.
 *
 * ## The guarantee
 *
 * The block holds steps, and the compiler is what says so to an author who writes the
 * old form: a block that recorded shell lines is a block whose `line(..)` no longer
 * resolves, and the compiler names it.
 *
 * The catalogue's own example of a scripted control flow keeps declaring what it
 * declares: the arm that was taken is a step of its own, and the arm that was not
 * taken is not in the plan at all.
 *
 * ## Why the instrument is the compiler
 *
 * Because the absence of a symbol is a fact the COMPILER states: no other instrument
 * can tell an author that `line(..)` is gone, and the script that writes it is the
 * one that has to be refused.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ScriptBlockCompilationTest {

    @TempDir
    lateinit var dir: Path

    private val scriptClasspath: List<String> =
        listOfNotNull(ScriptDefinition.domainJar(), ScriptDefinition.dslApiJar())

    private fun compile(name: String, text: String): ScriptCompilationResult {
        val script = dir.resolve(name)
        Files.writeString(script, text)
        val host = Kotlin24ScriptingHost(cache = CompiledScriptCache.Disabled)
        return host.compile(ScriptDefinition.file(script, classpath = scriptClasspath))
    }

    @Test
    fun `the block has no recorder of shell lines`() {
        val result = compile("lines.pipeline.kts", LINES)

        assertFalse(result.isSuccess, "the block still records shell lines: ${result.diagnostics}")
        assertTrue(
            result.diagnostics.any {
                it.message.contains("Unresolved reference") && it.message.contains("line")
            },
            "the compiler names the symbol that is gone: ${result.diagnostics}",
        )
    }

    @Test
    fun `the catalogue example of a scripted control flow declares the step of the arm that was taken`() {
        val example = repositoryRoot().resolve(
            "examples/catalogue/features/control-flow/scripted-control-flow/scripted-control-flow.pipeline.kts",
        )
        val result = compile("example.pipeline.kts", Files.readString(example))

        assertTrue(result.isSuccess, "the example did not compile: ${result.diagnostics}")
        val spec = result.value as PipelineSpec
        val echoes = spec.stages.single().steps.filterIsInstance<StepSpec.Echo>()
        assertEquals(
            1,
            echoes.size,
            "the example declares the arm that was taken, and only that arm: ${spec.stages.single().steps}",
        )
        assertEquals("x is positive", echoes.single().text)
    }

    /**
     * The root of the repository, found by walking up from the working directory.
     */
    private fun repositoryRoot(): Path {
        var candidate: Path? = Paths.get("").toAbsolutePath()
        while (candidate != null) {
            val directory = candidate
            if (Files.isRegularFile(directory.resolve("README.md")) &&
                Files.isRegularFile(directory.resolve("AGENTS.md"))
            ) {
                return directory
            }
            candidate = directory.parent
        }
        error("the repository root was not found from '${Paths.get("").toAbsolutePath()}'")
    }

    private companion object {
        /**
         * A block that records a line of shell, the way the block used to be written.
         */
        val LINES = """
            pipeline {
                stages {
                    stage("s") {
                        script {
                            line("echo hello")
                        }
                    }
                }
            }
        """.trimIndent()
    }
}
