package dev.rubentxu.pipeline.v2.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The identity at the border: what a raw text is allowed to become.
 *
 * ## What this pins
 *
 * Two questions, one owner.
 *
 * The first is "does this text name a run?". It is asked wherever the product
 * receives a run id it did not generate — an operator's argument, a value an
 * SDK supplied — and it is answered HERE, so that no border keeps its own copy
 * of the rule. A border with its own copy answers "no run" for a value that
 * another border accepts, and then the two disagree about the same run.
 *
 * The second is "why was this value refused?". It is answered here too, and for
 * the same reason: the store that persists the identity, the CLI that takes a
 * lease and the plane that recovers a run all have to say WHY a value was
 * turned away. If each phrases the refusal in its own words, they drift, and a
 * reader comparing two refusals of the same value reads two different reasons.
 *
 * ## The value travels as it arrived
 *
 * The identity IS the name in the durable plane, so a transformation at this
 * border would be a second spelling of the same run. This factory trims
 * nothing, folds nothing and escapes nothing: whether a value can NAME a
 * durable location is a separate question, answered by [RunId.isNameable],
 * and this one only says whether it names a run at all.
 */
class RunIdBorderTest {

    @Test
    fun `un valor en blanco no nombra ninguna ejecucion`() {
        assertNull(RunId.of(""), "an empty value names no run")
        assertNull(RunId.of("   "), "a blank value names no run")
        assertNull(RunId.of("\t\n "), "whitespace is blank, whatever it is made of")
    }

    @Test
    fun `un valor con contenido nombra la ejecucion y se conserva literal`() {
        assertEquals("run-42", RunId.of("run-42")?.value, "the value travels as it arrived")
        assertEquals(
            "  run-42  ",
            RunId.of("  run-42  ")?.value,
            "the border trims nothing: a trimmed spelling would be a second name for the same run",
        )
    }

    @Test
    fun `la razon de un nombre imposible la dice la identidad`() {
        val reason = RunId.unnameableReason("a/b")

        assertTrue(
            reason.contains("a/b"),
            "the refusal must name the value it turns away, or the reader cannot tell which run: $reason",
        )
        assertTrue(
            reason.contains("cannot name a durable location"),
            "the refusal must say what the value cannot do: $reason",
        )
    }

    @Test
    fun `un valor en blanco se rechaza con la misma frase`() {
        // A blank value is not a run that lost its name: it never had one. The
        // sentence is the same, so a reader does not have to learn two.
        val reason = RunId.unnameableReason("")

        assertTrue(
            reason.contains("cannot name a durable location"),
            "a value that never had a name is refused with the same sentence: $reason",
        )
    }
}
