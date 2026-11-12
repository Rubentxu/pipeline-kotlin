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
 * The notices the DSL offers to an author who writes the Jenkins form.
 *
 * ## The guarantee
 *
 * A form the product executes does not come with a notice the author cannot act on.
 *
 * `catchError { }` and `warnError { }` are the Jenkins forms, they run, and the
 * canonical body engine executes their bodies under the policy their catalogue entry
 * declares. Neither of them carries a deprecation notice: a notice that names an
 * exit a `.pipeline.kts` cannot write is a notice with no exit, and the compiler's
 * own diagnostics are the ones the author reads.
 *
 * The notice that DOES have an exit stays: the spec type `StepSpec.CatchError` is
 * reserved for the authoring shape, and its notice says exactly that. This row is
 * what keeps that notice from being removed with the others.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class DslDeprecationNoticeTest {

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
    fun `the Jenkins forms of the error scopes carry no deprecation notice`() {
        val (result, finished) = compile("scopes.pipeline.kts", SCOPES)

        assertTrue(result.isSuccess, "the script did not compile: ${result.diagnostics}")
        assertEquals(
            emptyList<ScriptingDiagnostic>(),
            finished.diagnostics.filter { it.message.contains("deprecated", ignoreCase = true) },
            "the author gets a notice they cannot act on: ${finished.diagnostics}",
        )
    }

    @Test
    fun `the spec type keeps the notice that reserves it for the authoring shape`() {
        val (result, finished) = compile("spec.pipeline.kts", SPEC)

        assertTrue(result.isSuccess, "the script did not compile: ${result.diagnostics}")
        val notices = finished.diagnostics.filter { it.message.contains("deprecated", ignoreCase = true) }
        assertEquals(1, notices.size, "the spec type carries exactly one notice: ${finished.diagnostics}")
        assertTrue(
            notices.single().message.contains("CatchError"),
            "the notice names the type it reserves: ${notices.single().message}",
        )
    }

    private companion object {
        /**
         * The two Jenkins forms, written the way a Jenkinsfile writes them.
         */
        val SCOPES = """
            pipeline {
                stages {
                    stage("scopes") {
                        catchError {
                            sh("make")
                        }
                        warnError("the build is not green") {
                            sh("make again")
                        }
                    }
                }
            }
        """.trimIndent()

        /**
         * The spec type used directly, which is what the notice is about.
         */
        val SPEC = """
            val spec = StepSpec.CatchError()
            println(spec.name)
        """.trimIndent()
    }
}
