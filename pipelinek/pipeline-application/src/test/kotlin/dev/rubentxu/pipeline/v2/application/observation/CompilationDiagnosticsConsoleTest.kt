package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.scripting.CacheKey
import dev.rubentxu.pipeline.v2.scripting.ScriptDiagnosticSeverity
import dev.rubentxu.pipeline.v2.scripting.ScriptOrigin
import dev.rubentxu.pipeline.v2.scripting.ScriptingDiagnostic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * The line that closes a compilation prints every diagnostic it counts.
 *
 * ## The guarantee
 *
 * The summary names how many diagnostics of each severity the compilation produced, and the lines
 * under it print each one of them with its position, worst first. A count with nothing under it is
 * a reason the operator is told exists and cannot read: a warnings-only compilation used to say
 * "1 warning" and print nothing, because the detail kept only the errors.
 *
 * ## The shape of a detail line
 *
 * An error reads `path:line:column: message`, as it did before warnings were printed. Every other
 * severity names itself after the position, the way the Kotlin compiler prints it:
 * `path:line:column: warning: message`. Without the word, a warning printed next to an error would
 * read as a second error.
 *
 * ## The family
 *
 * A compilation with an error or a fatal diagnostic is a failure, and no view can hide it. One with
 * only warnings, notes or debug output is a run line: the script compiled, and the exit stays 0.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CompilationDiagnosticsConsoleTest {

    @Test
    fun `a warnings-only compilation prints each warning with its position and stays a run line`() {
        val line = ConsoleFamilyLines.run(
            finished(ScriptingDiagnostic(ScriptDiagnosticSeverity.WARNING, TO_INT, 2, 11, SCRIPT)),
        )

        assertEquals(HumanConsoleRenderer.Family.RUN, line.family, "a warning was rendered as a failure")
        assertEquals(
            "Compilation finished with 1 warning (compiled)\n$SCRIPT:2:11: warning: $TO_INT",
            line.text,
        )
    }

    @Test
    fun `every diagnostic the summary counts is printed under it, worst first`() {
        val line = ConsoleFamilyLines.run(
            finished(
                ScriptingDiagnostic(ScriptDiagnosticSeverity.INFO, "a note", 1, 0, SCRIPT),
                ScriptingDiagnostic(ScriptDiagnosticSeverity.WARNING, "first warning", 2, 4, SCRIPT),
                ScriptingDiagnostic(ScriptDiagnosticSeverity.ERROR, "the error", 3, 1, SCRIPT),
                ScriptingDiagnostic(ScriptDiagnosticSeverity.DEBUG, "a trace", 4, 0, ""),
                ScriptingDiagnostic(ScriptDiagnosticSeverity.FATAL, "the fatal one", 5, 0, SCRIPT),
                ScriptingDiagnostic(ScriptDiagnosticSeverity.WARNING, "second warning", 6, 2, SCRIPT),
            ),
        )

        assertEquals(HumanConsoleRenderer.Family.FAILURE, line.family)
        assertEquals(
            listOf(
                "Compilation finished with 1 fatal and 1 error and 2 warnings and 1 info and 1 debug (compiled)",
                "$SCRIPT:5:0: fatal: the fatal one",
                "$SCRIPT:3:1: the error",
                "$SCRIPT:2:4: warning: first warning",
                "$SCRIPT:6:2: warning: second warning",
                "$SCRIPT:1:0: info: a note",
                "4:0: debug: a trace",
            ),
            line.text.lines(),
        )
    }

    @Test
    fun `a fatal diagnostic on its own is a failure`() {
        val line = ConsoleFamilyLines.run(
            finished(ScriptingDiagnostic(ScriptDiagnosticSeverity.FATAL, "the compiler gave up", 0, 0, SCRIPT)),
        )

        assertEquals(HumanConsoleRenderer.Family.FAILURE, line.family, "a fatal diagnostic was rendered as a run line")
        assertEquals(
            "Compilation finished with 1 fatal (compiled)\n$SCRIPT:0:0: fatal: the compiler gave up",
            line.text,
        )
    }

    @Test
    fun `a compilation with no diagnostics prints the summary alone`() {
        val line = ConsoleFamilyLines.run(finished())

        assertEquals(HumanConsoleRenderer.Family.RUN, line.family)
        assertEquals("Compilation finished (compiled)", line.text)
    }

    private fun finished(vararg diagnostics: ScriptingDiagnostic) = CompilationFinished(
        eventId = "e-2",
        runId = "run-1",
        sequence = 1L,
        occurredAt = Instant.EPOCH,
        cacheKey = CacheKey("hash", CacheKey.V3),
        diagnostics = diagnostics.toList(),
        origin = ScriptOrigin.COMPILED,
    )

    private companion object {
        const val SCRIPT = "/work/warn.pipeline.kts"
        const val TO_INT = "Use 'toInt' to give the type explicitly."
    }
}
