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
 * The line of an error the body raises is the line of the script, whichever file stored the
 * artifact the script was evaluated from.
 *
 * ## The guarantee
 *
 * The key of a stored artifact is the text of the script and what it is compiled against, not the
 * file it was read from, so two files with the same text share one artifact. The classes of that
 * artifact were compiled from the first file: they are named after it, and their frames name its
 * file. The error the body raises when the second file reuses the artifact points at the same line
 * of the same text, so it must print the same line, under the name of the second file.
 *
 * ## Why each row is here
 *
 * - The reuse from another file is the case the stored frames make wrong when the line is looked
 *   up by the name of the file: no frame names the second file, and the line falls to 0.
 * - The rows on [scriptLineOf] pin which frames are the script's: the frames of the class that
 *   was evaluated and of the classes nested in it, with a line. A frame of the DSL above the script,
 *   a class whose name only starts like the script's, and a frame with no line are not the line
 *   the author wrote; and with no class of the script, or no frame of it, the line is unknown.
 *
 * ## The instrument
 *
 * A real store in a directory of the row's own, and the host the product compiles with. The pure
 * rows hand [scriptLineOf] a throwable whose stack trace the row writes, so each row states the
 * frames it is about.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ReusedEvaluationErrorLineTest {

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

    private fun evaluate(name: String): CompilationFinished {
        val script = dir.resolve(name)
        Files.writeString(script, THROWN)
        val sink = RecordingSink()
        val host = Kotlin24ScriptingHost(eventSink = sink, cache = store)
        val result = host.compile(ScriptDefinition.file(script, classpath = scriptClasspath))
        val finished = sink.events.filterIsInstance<CompilationFinished>().single()
        assertFalse(result.isSuccess, "a script whose body throws was accepted")
        assertEquals(result.diagnostics, finished.diagnostics, "the event and the result tell two stories")
        return finished
    }

    private fun thrownThrough(vararg frames: StackTraceElement): Throwable =
        IllegalStateException(REASON).apply { stackTrace = arrayOf(*frames) }

    private fun frame(className: String, line: Int, file: String? = "first.pipeline.kts") =
        StackTraceElement(className, "<init>", file, line)

    @Test
    fun `a reuse from a file with another name points at the line that threw, under its own name`() {
        val stored = evaluate("first.pipeline.kts")
        val reused = evaluate("second.pipeline.kts")

        assertEquals(ScriptOrigin.COMPILED, stored.origin, "the first file did not compile, so nothing was stored")
        assertEquals(ScriptOrigin.REUSED, reused.origin, "two files with the same text did not share the artifact")
        assertEquals(
            listOf(ScriptingDiagnostic(ScriptDiagnosticSeverity.ERROR, REASON, 2, 0, "${dir.resolve("first.pipeline.kts")}")),
            stored.diagnostics,
            "the compilation that stored the artifact did not point at the line that threw",
        )
        assertEquals(
            listOf(ScriptingDiagnostic(ScriptDiagnosticSeverity.ERROR, REASON, 2, 0, "${dir.resolve("second.pipeline.kts")}")),
            reused.diagnostics,
            "the reuse lost the line, or named the file that stored the artifact",
        )
    }

    @Test
    fun `a frame of the DSL above the script is skipped, and the script's own frame gives the line`() {
        val thrown = thrownThrough(
            frame("dev.rubentxu.pipeline.v2.dsl.PipelineBuilder", 120, "PipelineBuilder.kt"),
            frame("First_pipeline", 4),
        )

        assertEquals(4, scriptLineOf(thrown, "First_pipeline"))
    }

    @Test
    fun `the frames of the evaluated class count whatever file they name`() {
        // What a reuse from another file evaluates: the stored classes name the file that stored them.
        val thrown = thrownThrough(frame("First_pipeline", 6, "first.pipeline.kts"))

        assertEquals(6, scriptLineOf(thrown, "First_pipeline"))
    }

    @Test
    fun `a class nested in the script is the script`() {
        val thrown = thrownThrough(
            frame("dev.rubentxu.pipeline.v2.dsl.StageBuilder", 40, "StageBuilder.kt"),
            frame("First_pipeline\$1\$1", 5),
            frame("First_pipeline", 3),
        )

        assertEquals(5, scriptLineOf(thrown, "First_pipeline"))
    }

    @Test
    fun `a class whose name only starts like the script's is not the script`() {
        val impostor = frame("First_pipelineX", 7)

        assertEquals(0, scriptLineOf(thrownThrough(impostor), "First_pipeline"))
        assertEquals(3, scriptLineOf(thrownThrough(impostor, frame("First_pipeline", 3)), "First_pipeline"))
    }

    @Test
    fun `a frame of the script with no line is skipped`() {
        val thrown = thrownThrough(frame("First_pipeline", -1), frame("First_pipeline", -2), frame("First_pipeline", 2))

        assertEquals(2, scriptLineOf(thrown, "First_pipeline"))
    }

    @Test
    fun `with no class of the script, or no frame of it, the line is unknown`() {
        val thrown = thrownThrough(frame("dev.rubentxu.pipeline.v2.dsl.PipelineBuilder", 120, "PipelineBuilder.kt"))

        assertEquals(0, scriptLineOf(thrownThrough(frame("First_pipeline", 4)), null))
        assertEquals(0, scriptLineOf(thrown, "First_pipeline"))
        assertEquals(0, scriptLineOf(thrownThrough(), "First_pipeline"))
    }

    private companion object {
        const val REASON = "boom: evaluation failed here"

        val THROWN = """
            // The body throws before it declares a pipeline.
            error("$REASON")

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
