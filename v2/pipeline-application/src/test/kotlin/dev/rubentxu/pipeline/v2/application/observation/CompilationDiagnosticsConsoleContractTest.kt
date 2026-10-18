package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.scripting.CacheKey
import dev.rubentxu.pipeline.v2.scripting.ScriptDiagnosticSeverity
import dev.rubentxu.pipeline.v2.scripting.ScriptingDiagnostic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The contract that a failed compilation names its own reasons (2026-10-18).
 *
 * Measured on the built distribution, not on the tree: a script with **nine**
 * compilation errors printed
 *
 * ```
 * [PipelineK] Compilation finished with 9 diagnostic(s)
 * Pipeline finished with FAILURE
 *   cause [SCHEMA]: Kotlin compilation failed
 * ```
 *
 * and `validate` printed exactly **one** of the nine — `lastOrNull`, the last
 * one. The cardinality was announced and the content withheld, which is worse
 * than either, because the user is told there are nine reasons and shown one
 * arbitrary one. The same shape printed "1 diagnostic(s)" on the repo's own
 * canonical `01-basic.pipeline.kts` on a `VALIDATION SUCCESSFUL` run: a
 * WARNING was counted exactly like an ERROR, so a bare number cannot say
 * whether a pipeline can start.
 *
 * These assertions cross the productive renderer (`ConsoleFamilyLines.run`)
 * on a pure function: no process, no ZIP, no wall clock. The end-to-end
 * observation that produced them is recorded in
 * `PRODUCT_FIRST_PRODUCTION_READY_2026-10-17.md` (H1.4).
 */
class CompilationDiagnosticsConsoleContractTest {

    private companion object {
        /** ESC and BEL are written as escapes, never as raw bytes in source. */
        const val ESC = "\u001B"
        const val BEL = "\u0007"
    }

    private fun diagnostic(
        severity: ScriptDiagnosticSeverity,
        message: String,
        line: Int = 1,
        column: Int = 1,
        path: String = "/w/p.pipeline.kts",
    ) = ScriptingDiagnostic(severity, message, line, column, path)

    private fun compilationFinished(vararg diagnostics: ScriptingDiagnostic): CompilationFinished =
        CompilationFinished(
            eventId = "evt-compilation-finished",
            runId = "run-1",
            sequence = 2L,
            occurredAt = Instant.parse("2026-10-18T00:00:00Z"),
            cacheKey = CacheKey("test", CacheKey.V1),
            diagnostics = diagnostics.toList(),
        )

    /** Nine errors: the exact cardinality measured on the distribution. */
    private fun nineErrors(): List<ScriptingDiagnostic> =
        (1..9).map { diagnostic(ScriptDiagnosticSeverity.ERROR, "err-$it", line = it) }

    @Test
    fun `every error is named, not just the last one`() {
        val errors = nineErrors()
        val text = ConsoleFamilyLines.run(compilationFinished(*errors.toTypedArray())).text
        for (i in 1..9) {
            assertTrue(text.contains("err-$i")) { "error $i is missing from:\n$text" }
        }
    }

    /**
     * The regression that mattered, in the sharpest form: `lastOrNull`
     * printed only the **ninth**, so the first error — the one an author
     * wrote first, and usually the root of the rest — was the one dropped.
     */
    @Test
    fun `the first error is not the one that gets dropped`() {
        val errors = nineErrors()
        val text = ConsoleFamilyLines.run(compilationFinished(*errors.toTypedArray())).text
        assertTrue(text.contains("err-1"), "the first error is the one an author wrote first")
    }

    @Test
    fun `a failure to compile is a FAILURE-family line, so no view can hide it`() {
        val line = ConsoleFamilyLines.run(
            compilationFinished(diagnostic(ScriptDiagnosticSeverity.ERROR, "boom")),
        )
        assertEquals(
            HumanConsoleRenderer.Family.FAILURE, line.family,
            "a failure a view can hide is a lie",
        )
    }

    /**
     * The anti-vacuo pair. The SAME event shape with no error is a `RUN` line
     * that names a warning and says nothing failed. Without this row, an
     * implementation that refused every compilation would satisfy all three
     * assertions above.
     */
    @Test
    fun `a warnings-only compilation stays a RUN line and names the severity`() {
        val line = ConsoleFamilyLines.run(
            compilationFinished(diagnostic(ScriptDiagnosticSeverity.WARNING, "unused variable")),
        )
        assertEquals(HumanConsoleRenderer.Family.RUN, line.family)
        assertTrue(line.text.contains("1 warning")) {
            "a bare count is the defect: got '${line.text}'"
        }
    }

    @Test
    fun `a clean compilation prints no count at all`() {
        val line = ConsoleFamilyLines.run(compilationFinished())
        assertEquals(HumanConsoleRenderer.Family.RUN, line.family)
        assertEquals("Compilation finished", line.text)
    }

    @Test
    fun `severities are counted separately and worst-first`() {
        val line = ConsoleFamilyLines.run(
            compilationFinished(
                diagnostic(ScriptDiagnosticSeverity.WARNING, "w"),
                diagnostic(ScriptDiagnosticSeverity.ERROR, "e1"),
                diagnostic(ScriptDiagnosticSeverity.ERROR, "e2"),
            ),
        )
        assertTrue(line.text.startsWith("Compilation finished with 2 errors and 1 warning")) {
            "got '${line.text}'"
        }
    }

    @Test
    fun `each error carries its position`() {
        val line = ConsoleFamilyLines.run(
            compilationFinished(
                diagnostic(ScriptDiagnosticSeverity.ERROR, "typo", line = 12, column = 34),
            ),
        )
        assertTrue(line.text.contains("/w/p.pipeline.kts:12:34: typo")) {
            "a message without a position cannot be acted on; got '${line.text}'"
        }
    }

    /**
     * A hostile compiler message must not be able to inject terminal escapes.
     * The error text is a NEW path into the console, and a new path into a
     * console is where injection lands; the message is external input.
     */
    @Test
    fun `an error message cannot carry terminal escapes`() {
        val line = ConsoleFamilyLines.run(
            compilationFinished(
                diagnostic(ScriptDiagnosticSeverity.ERROR, "bad$ESC]0;pwned$BEL"),
            ),
        )
        assertTrue(!line.text.contains(ESC)) {
            "ESC leaked into the console: ${line.text.toCharArray()}"
        }
    }
}