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
 * The notice the compiler offers to an author who writes the historical spelling.
 *
 * ## The guarantee
 *
 * A spelling that keeps working tells the author that it is the historical one, and it
 * names the spelling that replaces it: a notice that says "this is deprecated" without
 * saying what to write is a notice the author can only act on by reading the source.
 *
 * The value by default is named `defaultValue`, the name Jenkins gives it. A script that
 * writes `default = ` still compiles and still declares the same value, and the compiler
 * says that `defaultValue` is the name to write. The new spelling carries no notice at
 * all: a name that is the current one has nothing to announce.
 *
 * ## Why the instrument is the COMPILER
 *
 * Because the notice is the compiler's, and not a message the product prints later: an
 * author reads it where they write the script. The only place the compiler speaks is the
 * compilation of a script, so the rows compile a script and read the diagnostics it left.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ParametersDefaultNoticeTest {

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
    fun `the historical spelling carries a notice that names the spelling that replaces it`() {
        val (result, finished) = compile("historical.pipeline.kts", HISTORICAL)

        assertTrue(result.isSuccess, "the script did not compile: ${result.diagnostics}")
        val notices = finished.diagnostics.filter { it.message.contains("deprecated", ignoreCase = true) }
        assertEquals(1, notices.size, "the historical spelling carries exactly one notice: ${finished.diagnostics}")
        // The diagnostic quotes the DECLARATION before it says the notice, and the quoted
        // declaration carries the names of the parameters. What the DSL owns is the text
        // that follows, so that is the text the row reads.
        val notice = notices.single().message.substringAfter("is deprecated. ", "")
        assertTrue(
            notice.contains("defaultValue"),
            "the notice names the spelling that replaces it: ${notices.single().message}",
        )
    }

    @Test
    fun `the current spelling carries no notice`() {
        val (result, finished) = compile("current.pipeline.kts", CURRENT)

        assertTrue(result.isSuccess, "the script did not compile: ${result.diagnostics}")
        assertEquals(
            emptyList<ScriptingDiagnostic>(),
            finished.diagnostics.filter { it.message.contains("deprecated", ignoreCase = true) },
            "a name that is the current one announces nothing: ${finished.diagnostics}",
        )
    }

    private companion object {
        /**
         * A declaration written with the historical spelling.
         */
        val HISTORICAL = """
            pipeline {
                parameters {
                    string("target", default = "staging")
                }
            }
        """.trimIndent()

        /**
         * The same declaration written with the name that replaces it.
         */
        val CURRENT = """
            pipeline {
                parameters {
                    string("target", defaultValue = "staging")
                }
            }
        """.trimIndent()
    }
}
