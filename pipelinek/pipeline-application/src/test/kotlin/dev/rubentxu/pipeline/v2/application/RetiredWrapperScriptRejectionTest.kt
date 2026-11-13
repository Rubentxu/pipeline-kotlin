package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.runtime.NoopPipelineTelemetry
import dev.rubentxu.pipeline.v2.scripting.JarCompiledScriptCache
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * A script written before the retirement, and what the product does with it.
 *
 * ## The guarantee under trial
 *
 * That a script that writes a wrapper this product cannot execute is REFUSED,
 * and refused in the compilation: the script produces no plan, so no run
 * starts, so nothing the wrapper wrapped ever runs.
 *
 * ## Why the rejection has to be in the compilation
 *
 * Because the alternative was the run refusing it: the script compiled, the
 * plan was built, the run started, and the refusal came at the step. That is
 * a rejection an author learns about too late, and it is the reason the
 * authoring surface was retired. The refusal has to be where the mistake is.
 *
 * ## Why the control row is here
 *
 * Because a compilation can fail for a reason that has nothing to do with the
 * form under trial — a wrong import, a wrong shape, a broken recipe. The
 * control compiles the same script with the same instrument and the same
 * source, and the only difference is the retired form: without it, the script
 * compiles and yields a plan. Without the control, the three rows above would
 * pass with an instrument that never compiled anything.
 *
 * ## What this file does NOT affirm
 *
 * It does not affirm what a PLAN that carries the retired key does: that is
 * the admission's decision, measured where the admission decides it.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class RetiredWrapperScriptRejectionTest {

    /**
     * A script that writes the node of Jenkins does not compile.
     */
    @Test
    fun `a script that writes the node of Jenkins does not compile`() {
        refusesToCompile(
            body = "node { echo(\"hola\") }",
            retiredName = "node",
        )
    }

    /**
     * A script that writes the ansi colour of Jenkins does not compile.
     */
    @Test
    fun `a script that writes the ansi colour of Jenkins does not compile`() {
        refusesToCompile(
            body = "ansiColor(\"xterm\") { echo(\"hola\") }",
            retiredName = "ansiColor",
        )
    }

    /**
     * A script that loads another script does not compile.
     */
    @Test
    fun `a script that loads another script does not compile`() {
        refusesToCompile(
            body = "load(\"otro.pipeline.kts\")",
            retiredName = "load",
        )
    }

    /**
     * The same script, without the retired form, compiles.
     *
     * The control of the three rows above: the instrument really compiles, and
     * the refusal they measure is the one the retired form causes.
     */
    @Test
    fun `the same script without the retired form compiles`() {
        val script = writeScript("modern.pipeline.kts", scriptWith("echo(\"hola\")"))

        val compiled = compile(script)

        assertNull(
            compiled.compilation.failure,
            "the script without a retired form did not compile: the instrument does not " +
                "compile, and the rows that measure a refusal prove nothing",
        )
        assertNotNull(
            compiled.pipeline,
            "the script without a retired form produced no plan: the instrument does not " +
                "build a plan, and the rows that measure a refusal prove nothing",
        )
    }

    /**
     * Compiles a script whose stage carries [body], and requires that the
     * compilation is refused and that the refusal NAMES the retired form.
     */
    private fun refusesToCompile(body: String, retiredName: String) {
        val script = writeScript("legacy.pipeline.kts", scriptWith(body))

        val compiled = compile(script)

        assertNull(
            compiled.pipeline,
            "the script that writes `$retiredName` produced a plan: the language still accepts " +
                "a wrapper it cannot execute, and the run would be the one refusing it",
        )
        assertNotNull(
            compiled.compilation.failure,
            "the script that writes `$retiredName` failed without a reason: a rejection the " +
                "author cannot read is not a rejection",
        )

        val diagnostics = compiled.compilation.result.diagnostics
        assertTrue(
            diagnostics.any { diagnostic -> diagnostic.message.contains(retiredName) },
            "the refusal does not name the form the author wrote: the author is left with a " +
                "message about something else. The diagnostics were " +
                "${diagnostics.map { diagnostic -> diagnostic.message }}",
        )
    }

    /**
     * Compiles the script at [path] the way the product compiles the script of
     * a run.
     */
    private fun compile(script: Path) = compileDurableScript(
        eventStore = DiscardingEventSink,
        runId = RUN_ID,
        scriptPath = script,
        scriptContent = Files.readString(script),
        pluginJars = emptyList(),
        telemetry = NoopPipelineTelemetry,
        cache = JarCompiledScriptCache(workDir.resolve("compiled")),
    )

    /**
     * A sink that keeps nothing: what this file measures is the compilation,
     * and the events of a compilation are not part of it.
     */
    private object DiscardingEventSink : EventSink {
        override fun append(event: DomainEvent) = Unit
        override fun eventsFor(runId: String): Sequence<DomainEvent> = emptySequence()
    }

    /**
     * The eager DSL, with the stage body the row writes.
     */
    private fun scriptWith(body: String): String =
        "pipeline {\n    stages {\n        stage(\"legacy\") {\n            $body\n        }\n    }\n}\n"

    /**
     * Writes the script at [name] with [content] and returns its path.
     */
    private fun writeScript(name: String, content: String): Path {
        val script = workDir.resolve(name)
        Files.writeString(script, content)
        return script
    }

    @TempDir
    lateinit var workDir: Path

    private companion object {
        const val RUN_ID = "run-retired-wrapper"
    }
}
