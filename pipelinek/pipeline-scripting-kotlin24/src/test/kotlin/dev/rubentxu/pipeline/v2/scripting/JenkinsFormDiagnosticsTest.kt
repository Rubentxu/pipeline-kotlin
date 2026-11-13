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
 * The diagnostic the compiler raises about a form of Jenkins says what to
 * write instead.
 *
 * ## The guarantee
 *
 * The suggestion is APPENDED to the message of the diagnostic that caused it,
 * and it changes nothing else: the severity stays the one of the error, and
 * the count of the diagnostics stays the count of what the compiler said. A
 * script that writes no form of Jenkins compiles with no diagnostics at all.
 *
 * The suggestion is DERIVED on every compilation and it is not kept: the
 * store holds the reports of the compiler RAW. A script that does not compile
 * is not stored — the store keeps the artifact of a compilation that
 * succeeded, and nothing else — so the second pass compiles again and says
 * the same thing.
 *
 * ## Why each row is here
 *
 * - The string of single quotes and the property of the environment are the two
 *   shapes a translated Jenkinsfile meets, and each one has to name the
 *   PipelineK form.
 * - The clean script is the case that must NOT be touched: a suggestion on a
 *   diagnostic that is not about a form of Jenkins would send the author to
 *   change a script that is already right.
 * - The second pass is the case where the suggestion could be lost, because a
 *   store could serve the script without saying what the compiler said.
 *
 * ## The instrument
 *
 * The host the product compiles with, and a real store in a directory of the
 * row's own.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class JenkinsFormDiagnosticsTest {

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

    private val store: JarCompiledScriptCache by lazy { JarCompiledScriptCache(dir.resolve("cache")) }

    private fun compile(script: Path): Pair<ScriptCompilationResult, CompilationFinished> {
        val sink = RecordingSink()
        val host = Kotlin24ScriptingHost(eventSink = sink, cache = store)
        val result = host.compile(ScriptDefinition.file(script, classpath = scriptClasspath))
        val finished = sink.events.filterIsInstance<CompilationFinished>().single()
        assertEquals(result.diagnostics, finished.diagnostics, "the event and the result tell two stories")
        return result to finished
    }

    private fun write(name: String, text: String): Path {
        val script = dir.resolve(name)
        Files.writeString(script, text)
        return script
    }

    @Test
    fun `the diagnostic of the string of single quotes says the string of double quotes`() {
        val script = write("single-quotes.pipeline.kts", SINGLE_QUOTES)

        val (result, _) = compile(script)

        val diagnostic = result.diagnostics.single()
        assertEquals(
            ScriptDiagnosticSeverity.ERROR,
            diagnostic.severity,
            "the suggestion turned the error into something else",
        )
        assertTrue(
            diagnostic.message.contains("a string is written between double quotes: \"make\""),
            "the diagnostic does not say what to write: ${diagnostic.message}",
        )
    }

    @Test
    fun `the diagnostic of the property of the environment says the call that reads it`() {
        val script = write("env-property.pipeline.kts", ENV_PROPERTY)

        val (result, _) = compile(script)

        val suggested = result.diagnostics.filter { diagnostic -> diagnostic.message.contains("In PipelineK:") }
        assertEquals(
            1,
            suggested.size,
            "the diagnostics of the property of the environment do not name one form: ${result.diagnostics}",
        )
        assertTrue(
            suggested.single().message.contains("the variable of the environment is read with env(\"BRANCH_NAME\")"),
            "the diagnostic does not say what to write: ${suggested.single().message}",
        )
    }

    @Test
    fun `a script that writes no form of Jenkins compiles with no diagnostics`() {
        val script = write("clean.pipeline.kts", CLEAN)

        val (result, _) = compile(script)

        assertTrue(result.isSuccess, "the clean script did not compile: ${result.diagnostics}")
        assertEquals(
            emptyList<ScriptingDiagnostic>(),
            result.diagnostics,
            "the compilation carries a diagnostic the script did not cause",
        )
    }

    @Test
    fun `the script says the same thing the second time`() {
        val script = write("single-quotes.pipeline.kts", SINGLE_QUOTES)

        val (cold, coldFinished) = compile(script)
        val (again, againFinished) = compile(script)

        assertEquals(ScriptOrigin.COMPILED, coldFinished.origin, "the first pass did not compile")
        assertEquals(
            ScriptOrigin.COMPILED,
            againFinished.origin,
            "a script that did not compile was stored, and the second pass was served by the store",
        )
        assertEquals(
            cold.diagnostics,
            again.diagnostics,
            "the second pass did not say what the first one said",
        )
    }

    private companion object {
        val SINGLE_QUOTES = """
            pipeline {
                stages {
                    stage("build") {
                        sh('make')
                    }
                }
            }
        """.trimIndent()

        val ENV_PROPERTY = """
            pipeline {
                stages {
                    stage("build") {
                        sh("echo ${'$'}{env.BRANCH_NAME}")
                    }
                }
            }
        """.trimIndent()

        val CLEAN = """
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
