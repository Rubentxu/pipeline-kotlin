package dev.rubentxu.pipeline.v2.output.store

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [DiagnosticText] is the narrowing every Output Plane refusal depends on before it reaches a
 * consumer that prints one line per record.
 *
 * The rule it exists for: an exception message is not ours to bound. A path carrying a newline is
 * legal on Linux, and Output Plane stream ids reach the CLI as arguments, so an `IOException` raised
 * while reading one can carry a newline that the consumer would render as an extra record-shaped
 * line. Before this rule had a name, three call sites implemented it and one -- `MainInspectCli` --
 * did not, and the one that did not is the defect these tests lock shut.
 */
class DiagnosticTextTest {

    /** A named throwable, so the rendered simple name is deterministic. */
    private class Boom(message: String?) : RuntimeException(message)

    // --- the newline is the property that matters ------------------------------

    @Test
    fun `a newline in the message cannot survive into the rendered diagnostic`() {
        val rendered = DiagnosticText.from(Boom("head\nforged-row"))

        assertFalse('\n' in rendered, "a newline reached the diagnostic: $rendered")
        assertEquals("Boom: head forged-row", rendered)
    }

    @Test
    fun `a carriage return is neutralised too`() {
        // The console treats CR as a return, so a lone CR forges a line the same way \n does.
        val rendered = DiagnosticText.from(Boom("head\rforged-row"))

        assertFalse('\r' in rendered, "a carriage return reached the diagnostic: $rendered")
    }

    @Test
    fun `a newline past the truncation point cannot come back`() {
        // Truncate-then-replace, not replace-then-truncate: reversing the order can cut a replacement
        // in half, and a bare '\r' is as forgeable as '\n'.
        val rendered = DiagnosticText.from(Boom("x".repeat(DiagnosticText.MAX_LEN) + "\nforged"))

        assertFalse('\n' in rendered, "a newline reached the diagnostic past MAX_LEN")
        assertFalse('\r' in rendered)
    }

    @Test
    fun `every rendered diagnostic is exactly one line`() {
        val messages = listOf(
            "a\nb",
            "a\n\n\nb",
            "\nleading",
            "trailing\n",
            "x".repeat(DiagnosticText.MAX_LEN) + "\ntail",
            null,
            "",
            "   ",
        )

        for (message in messages) {
            val rendered = DiagnosticText.from(Boom(message))

            assertFalse('\n' in rendered, "newline survived for message=$message")
            assertFalse('\r' in rendered, "carriage return survived for message=$message")
        }
    }

    // --- shape of the rendering -------------------------------------------------

    @Test
    fun `the message is truncated to MAX_LEN`() {
        val rendered = DiagnosticText.from(Boom("y".repeat(DiagnosticText.MAX_LEN * 3)))

        // "Boom: " plus exactly MAX_LEN of message: the cap bounds the MESSAGE, not the whole line,
        // so the two are asserted separately rather than as one length.
        assertEquals("Boom: " + "y".repeat(DiagnosticText.MAX_LEN), rendered)
    }

    @Test
    fun `a message exactly at the cap is kept whole`() {
        val atCap = "z".repeat(DiagnosticText.MAX_LEN)

        assertEquals("Boom: $atCap", DiagnosticText.from(Boom(atCap)))
    }

    @Test
    fun `a missing or blank message renders the class alone`() {
        // Not "Class: " with a dangling separator: a consumer splitting on ": " would otherwise see
        // an empty second field.
        assertEquals("Boom", DiagnosticText.from(Boom(null)))
        assertEquals("Boom", DiagnosticText.from(Boom("")))
        assertEquals("Boom", DiagnosticText.from(Boom("   ")))
        assertFalse(DiagnosticText.from(Boom("")).endsWith(": "))
    }

    @Test
    fun `a message that is only newlines is blank, not content`() {
        // take() keeps the newlines, then the replace turns them into spaces, which IS blank. The
        // blank check therefore has to run AFTER the narrowing, and it does.
        assertEquals("Boom", DiagnosticText.from(Boom("\n\n")))
    }

    @Test
    fun `the rendered diagnostic always names the throwable type`() {
        // A stack trace with no message is still a fact about WHICH operation failed.
        assertTrue(DiagnosticText.from(Boom(null)).startsWith("Boom"))
        assertTrue(DiagnosticText.from(java.io.IOException("disk")).startsWith("IOException"))
    }

    @Test
    fun `a custom Throwable subclass keeps its own simple name`() {
        class Specific : RuntimeException("nope")

        assertEquals("Specific: nope", DiagnosticText.from(Specific()))
    }
}
