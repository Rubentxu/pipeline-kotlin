package dev.rubentxu.pipeline.v2.scripting

import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * A script that compiles and then throws while it is evaluated is refused, and the closing
 * event of the compilation carries the reason.
 *
 * ## The guarantee
 *
 * The console and the JSON document both render the diagnostics of [CompilationFinished], and
 * nothing else. A reason that lives only in the [ScriptCompilationResult] is a refusal nobody can
 * read: `validate` counted it and printed "1 error" without saying which. So the event and the
 * result carry the SAME list.
 *
 * ## Why each row is here
 *
 * - The thrown row is the plain case: the author's top-level body calls `error(...)`.
 * - The consumption-gate row is the refusal the DSL itself raises while the pipeline is built.
 *   Its text is the one the author has to read to find the discarded carrier.
 * - The compile-error row is the control. A script the compiler refuses already reached the
 *   event, and it must reach it exactly as before: each error once, with the compiler's
 *   position, and no evaluation error added to it.
 *
 * The line of an evaluation error is read from the frame of the script in the stack trace. The
 * JVM does not record a column, so the column stays 0, which is what "unknown" already means in
 * these diagnostics.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class EvaluationErrorDiagnosticTest {

    @TempDir
    lateinit var dir: Path

    private class RecordingSink : EventSink {
        val events = mutableListOf<DomainEvent>()

        override fun append(event: DomainEvent) {
            events += event
        }

        override fun eventsFor(runId: String): Sequence<DomainEvent> = events.asSequence()
    }

    private val scriptClasspath: List<String> =
        listOfNotNull(ScriptDefinition.domainJar(), ScriptDefinition.dslApiJar())

    private fun evaluate(name: String, text: String): Pair<ScriptCompilationResult, CompilationFinished> {
        val script = dir.resolve(name)
        Files.writeString(script, text)
        val sink = RecordingSink()
        val host = Kotlin24ScriptingHost(eventSink = sink, cache = CompiledScriptCache.Disabled)
        val result = host.compile(ScriptDefinition.file(script, classpath = scriptClasspath))
        return result to sink.events.filterIsInstance<CompilationFinished>().single()
    }

    private fun errorsOf(diagnostics: List<ScriptingDiagnostic>): List<ScriptingDiagnostic> =
        diagnostics.filter { it.severity == ScriptDiagnosticSeverity.ERROR }

    @Test
    fun `a body that throws puts its message in the closing event, at the line that threw`() {
        val (result, finished) = evaluate(
            "thrown.pipeline.kts",
            """
            // The body throws before it declares a pipeline.
            error("boom: evaluation failed here")

            pipeline {
                stages {
                    stage("build") {
                        sh("make")
                    }
                }
            }
            """.trimIndent(),
        )

        assertFalse(result.isSuccess, "a script whose body throws was accepted")
        val errors = errorsOf(finished.diagnostics)
        assertEquals(
            listOf("boom: evaluation failed here"),
            errors.map { it.message },
            "the closing event did not carry the reason: ${finished.diagnostics}",
        )
        assertEquals(2 to 0, errors.single().line to errors.single().column)
        assertEquals(dir.resolve("thrown.pipeline.kts").toString(), errors.single().path)
        assertEquals(result.diagnostics, finished.diagnostics, "the event and the result tell two stories")
    }

    @Test
    fun `an inline script that throws carries the reason with no position`() {
        val sink = RecordingSink()
        val host = Kotlin24ScriptingHost(eventSink = sink, cache = CompiledScriptCache.Disabled)

        val result = host.compile(
            ScriptDefinition.inline("error(\"boom: inline\")", classpath = scriptClasspath),
        )

        val finished = sink.events.filterIsInstance<CompilationFinished>().single()
        assertFalse(result.isSuccess, "an inline script whose body throws was accepted")
        assertEquals(
            listOf(ScriptingDiagnostic(ScriptDiagnosticSeverity.ERROR, "boom: inline", 0, 0, "<inline>")),
            errorsOf(finished.diagnostics),
            "an inline script has no file, so no frame of it can give a line",
        )
    }

    @Test
    fun `the consumption gate's refusal reaches the closing event with its text`() {
        val (result, finished) = evaluate(
            "unconsumed.pipeline.kts",
            """
            pipeline {
                stages {
                    stage("build") {
                        val unused = scmGit(url = "https://example.invalid/r.git")
                        sh("make")
                    }
                }
            }
            """.trimIndent(),
        )

        assertFalse(result.isSuccess, "a stage that discards a carrier was accepted")
        val errors = errorsOf(finished.diagnostics)
        assertEquals(1, errors.size, "the closing event did not carry exactly one reason: ${finished.diagnostics}")
        val refusal = errors.single()
        assertEquals(
            true,
            refusal.message.startsWith("Pure builder consumption gate: "),
            "the refusal lost its text: ${refusal.message}",
        )
        // The gate refuses when the stage closes, so the frame of the script is the call to `stage`.
        assertEquals(3, refusal.line, "the refusal does not point at the stage that discarded the carrier")
        assertEquals(result.diagnostics, finished.diagnostics, "the event and the result tell two stories")
    }

    @Test
    fun `a script that does not compile reports each compiler error once and nothing else`() {
        val (result, finished) = evaluate(
            "broken.pipeline.kts",
            """
            val count: Int = "not a number"

            pipeline {
                stages {
                    stage("build") {
                        sh("make")
                    }
                }
            }
            """.trimIndent(),
        )

        assertFalse(result.isSuccess, "a script with a type error compiled")
        val errors = errorsOf(finished.diagnostics)
        assertEquals(listOf(1 to 16), errors.map { it.line to it.column }, "the compiler's errors changed: $errors")
        assertEquals(result.diagnostics, finished.diagnostics, "the event and the result tell two stories")
    }
}
