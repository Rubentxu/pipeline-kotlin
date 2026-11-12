package dev.rubentxu.pipeline.v2.scripting

import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The diagnostics of a compilation are the ones the author's script caused.
 *
 * ## The guarantee
 *
 * The closing event of a compilation carries what the compiler said about the script, with its
 * position, and nothing the product's own configuration makes the compiler say on every run.
 *
 * The script definition turns the compiler's fast jar file system off, and the compiler answers
 * that option with a note: "Using outdated version of JAR FS: it might make your build slower", with
 * no file and no position. It was printed for every script, so a script without a single warning
 * compiled "with 1 info", and the operator was shown the count of a diagnostic they did not cause
 * and could do nothing about. The host leaves that note out of the list, and the console and the
 * JSON document, which both read the list, leave it out the same way.
 *
 * ## Why each row is here
 *
 * - The clean script is the case the note polluted: it must compile with no diagnostics at all.
 * - The warning is a real one from the compiler, and it must still arrive, alone, with its
 *   position: leaving the note out must not leave the author's warnings out with it.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class CompilationDiagnosticsTest {

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

    private fun compile(name: String, text: String): Pair<ScriptCompilationResult, CompilationFinished> {
        val script = dir.resolve(name)
        Files.writeString(script, text)
        val sink = RecordingSink()
        val host = Kotlin24ScriptingHost(eventSink = sink, cache = CompiledScriptCache.Disabled)
        val result = host.compile(ScriptDefinition.file(script, classpath = scriptClasspath))
        return result to sink.events.filterIsInstance<CompilationFinished>().single()
    }

    @Test
    fun `a script without warnings compiles with no diagnostics at all`() {
        val (result, finished) = compile("clean.pipeline.kts", PIPELINE)

        assertTrue(result.isSuccess, "the clean script did not compile: ${result.diagnostics}")
        assertEquals(
            emptyList<ScriptingDiagnostic>(),
            finished.diagnostics,
            "the closing event carries a diagnostic the script did not cause",
        )
        assertEquals(result.diagnostics, finished.diagnostics, "the event and the result tell two stories")
    }

    @Test
    fun `a warning the compiler raises about the script arrives alone and with its position`() {
        val (result, finished) = compile("warn.pipeline.kts", "val n = 1 as Int\n\n$PIPELINE")

        assertTrue(result.isSuccess, "a script with a warning did not compile: ${result.diagnostics}")
        assertEquals(
            listOf(
                ScriptingDiagnostic(
                    ScriptDiagnosticSeverity.WARNING,
                    "Use 'toInt' to give the type explicitly.",
                    1,
                    11,
                    dir.resolve("warn.pipeline.kts").toString(),
                ),
            ),
            finished.diagnostics,
        )
        assertEquals(result.diagnostics, finished.diagnostics, "the event and the result tell two stories")
    }

    private companion object {
        val PIPELINE = """
            pipeline {
                stages {
                    stage("build") {
                        sh("make")
                    }
                }
            }
        """.trimIndent()
    }
}
