package dev.rubentxu.pipeline.v2.scripting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * S4-SRANGE — a call whose source range cannot be located is a REFUSAL, not a skip.
 *
 * ## What is wrong today
 *
 * `ScriptedSourceLowering.rewrite` resolves every mapped call's location against the
 * original text and, when that fails, does this:
 *
 * ```kotlin
 * if (start < 0 || start + call.sourceLength > text.length) continue
 * ```
 *
 * The call is then simply left alone. The lowering still returns
 * [ScriptedSourceLowering.LoweringResult.Generated], so the caller is told it has a
 * generated program, and that program contains a bare `sh(...)` / `isUnix(...)` with no
 * receiver.
 *
 * The user then gets `Unresolved reference 'sh'` pointing at **generated** code they never
 * wrote. That is not a fail-closed refusal, it is a displaced error: the information needed
 * to explain what happened exists — the mapper produced a location that does not exist in
 * the text the mapper was given — and it is thrown away in favour of a symptom three layers
 * downstream.
 *
 * This is the same shape as the defect S4-A1 already fixed in this area. There, an eager
 * `sh` was not rewritten and the host rejected the script at compile time. The
 * *consequence* was fixed; the *cause* — a dropped rewrite reported as success — was left
 * behind, and this is where it still lives.
 *
 * ## Why the unresolvable location is reachable at all
 *
 * [ScriptedSourceMapper] is a port. Production's PSI mapper derives locations from the
 * same text it parses, so today it cannot produce one that fails to resolve. But the
 * lowering is the component that must not silently drop a mapped call, and the port is
 * explicitly open. Driving it with a mapper that returns a location the text does not have
 * is not a contrivance: it is the exact input class the current `continue` exists to
 * swallow, and a defence that only works for the implementation that happens to be wired
 * today is not a defence.
 *
 * The two failure modes are distinguished because they are different operator mistakes:
 *
 * ```text
 * unresolvable location   the mapper pointed at a line/column this text does not have
 * length past the end     the mapper claimed a source extent the text does not have
 * ```
 */
class S4SourceRangeFailsClosedTest {

    private val sourceId = ScriptedSourceId("s4-srange.pipeline.kts")

    private fun text() = "sh(\"echo hi\")\n"

    private fun mapperReturning(calls: List<ScriptedMappedCall>): ScriptedSourceMapper =
        ScriptedSourceMapper { ScriptedSourceMapping.Mapped(calls = calls) }

    // ------------------------------------------------------------------ the refusal

    @Test
    fun `a mapped call whose location does not exist in the text refuses the lowering`() {
        // Line 40 is past the end of a one-line source. Nothing in the text can satisfy it.
        val ghost = ScriptedMappedCall(
            kind = ScriptedCallKind.Shell(script = "\"echo hi\"", returnMode = ScriptedShellReturnMode.NONE),
            location = ScriptedSourceLocation(sourceId, line = 40, column = 1),
            sourceLength = 12,
        )

        val result = ScriptedSourceLowering.lower(
            sourceId = sourceId,
            sourceText = text(),
            mapper = mapperReturning(listOf(ghost)),
            facadeSchemaVersion = "test",
        )

        assertTrue(
            result is ScriptedSourceLowering.LoweringResult.InvalidSyntax,
            "A mapped call the lowering cannot locate MUST be refused. Reporting `Generated` while " +
                "silently dropping the call is what produced the displaced `Unresolved reference` " +
                "failure. Got $result",
        )
        val diagnostics = (result as ScriptedSourceLowering.LoweringResult.InvalidSyntax).diagnostics
        assertEquals(
            1,
            diagnostics.size,
            "one unlocatable call, one diagnostic: $diagnostics",
        )
        assertEquals(
            40,
            diagnostics.single().line,
            "the diagnostic MUST point at the location the mapper claimed, so the operator can go " +
                "and look at the line the mapper was talking about. Diagnostics=$diagnostics",
        )
    }

    @Test
    fun `a mapped call whose source length runs past the end of the text is refused, and named as such`() {
        // Line 1 resolves fine; the 9000-character extent is the lie. A different operator
        // mistake from a missing line, so it must not borrow that explanation.
        val overreaching = ScriptedMappedCall(
            kind = ScriptedCallKind.Shell(script = "\"echo hi\"", returnMode = ScriptedShellReturnMode.NONE),
            location = ScriptedSourceLocation(sourceId, line = 1, column = 1),
            sourceLength = 9_000,
        )

        val result = ScriptedSourceLowering.lower(
            sourceId = sourceId,
            sourceText = text(),
            mapper = mapperReturning(listOf(overreaching)),
            facadeSchemaVersion = "test",
        )

        assertTrue(
            result is ScriptedSourceLowering.LoweringResult.InvalidSyntax,
            "A source extent that runs past the end of the text is a mapper defect and must be " +
                "refused, not skipped. Got $result",
        )
        val message = (result as ScriptedSourceLowering.LoweringResult.InvalidSyntax)
            .diagnostics.single().message
        assertTrue(
            message.contains("extent") || message.contains("length"),
            "the diagnostic must distinguish an over-long extent from a missing location, because " +
                "they are different defects with different fixes. Got: $message",
        )
    }

    // ------------------------------------------------------------------ controls

    @Test
    fun `CONTROL - a locatable call is still rewritten, so the harness is not what refuses`() {
        val real = ScriptedMappedCall(
            kind = ScriptedCallKind.Shell(script = "\"echo hi\"", returnMode = ScriptedShellReturnMode.NONE),
            location = ScriptedSourceLocation(sourceId, line = 1, column = 1),
            sourceLength = 12,
        )

        val result = ScriptedSourceLowering.lower(
            sourceId = sourceId,
            sourceText = text(),
            mapper = mapperReturning(listOf(real)),
            facadeSchemaVersion = "test",
        )

        assertTrue(
            result is ScriptedSourceLowering.LoweringResult.Generated,
            "the ordinary case must still produce a program. A lowering that refused everything " +
                "would pass the two tests above and be useless. Got $result",
        )
        val generated = (result as ScriptedSourceLowering.LoweringResult.Generated).source
        assertTrue(
            generated.contains("steps.sh("),
            "and it must actually carry the rewrite.\nGENERATED WAS:\n$generated",
        )
    }

    @Test
    fun `CONTROL - a locatable call on a later line resolves, because offsetOf walks the text`() {
        val second = "val ignored = 1\nsh(\"echo hi\")\n"
        val real = ScriptedMappedCall(
            kind = ScriptedCallKind.Shell(script = "\"echo hi\"", returnMode = ScriptedShellReturnMode.NONE),
            location = ScriptedSourceLocation(sourceId, line = 2, column = 1),
            sourceLength = 12,
        )

        val result = ScriptedSourceLowering.lower(
            sourceId = sourceId,
            sourceText = second,
            mapper = mapperReturning(listOf(real)),
            facadeSchemaVersion = "test",
        )

        assertTrue(
            result is ScriptedSourceLowering.LoweringResult.Generated,
            "a call on line 2 of a two-line source is locatable and must not be refused. " +
                "Refusing it would mean the refusal is keyed on something other than resolution. " +
                "Got $result",
        )
    }
}
