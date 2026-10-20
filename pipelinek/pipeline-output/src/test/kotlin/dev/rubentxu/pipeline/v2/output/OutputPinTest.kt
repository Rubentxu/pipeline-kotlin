package dev.rubentxu.pipeline.v2.output

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A pin is what stops the Output Plane from pruning bytes a reader is still looking at, so its two
 * safety properties are the point of the type: it must name a real range of a real stream, and it
 * must stop protecting that range at the instant it says it does.
 * `isExpiredAt` takes the clock as an ARGUMENT rather than reading one, which is what makes the
 * expiry boundary testable at all — and the boundary is the thing worth pinning, because `<` and `<=`
 * disagree about exactly one instant and that instant is the one an operator sees.
*/
class OutputPinTest {

    private fun pin(
        range: LongRange = 0L..99L,
        holder: String = "inspect-cli",
        reason: String = "transcript read",
        createdAtMs: Long = 1_000L,
        expiresAtMs: Long? = 2_000L,
    ) = OutputPin(
        pinId = OutputPinId("pin-1"),
        stream = OutputStreamId("run-7/op/stdout"),
        range = range,
        holder = holder,
        reason = reason,
        createdAtMs = createdAtMs,
        expiresAtMs = expiresAtMs,
    )

    // --- the expiry boundary -------------------------------------------------

    @Test
    fun `a pin expires AT its instant, not after it`() {
        val p = pin(expiresAtMs = 2_000L)

        assertFalse(p.isExpiredAt(1_999L), "one millisecond before, still holding")
        assertTrue(p.isExpiredAt(2_000L), "at the instant itself the pin has expired")
        assertTrue(p.isExpiredAt(2_001L), "after it, expired")
    }

    @Test
    fun `a pin with no expiry never expires`() {
        val p = pin(expiresAtMs = null)

        assertFalse(p.isExpiredAt(0L))
        assertFalse(p.isExpiredAt(Long.MAX_VALUE), "no expiry means no instant releases it")
    }

    @Test
    fun `isExpiredAt reads the clock it is given, not a real one`() {
        val p = pin(expiresAtMs = 2_000L)

        // The same pin answers differently for two different instants, which is only possible if
        // the instant is an input. Without that, this test would need a sleep and a real clock.
        assertFalse(p.isExpiredAt(1_000L))
        assertTrue(p.isExpiredAt(5_000L))
    }

    // --- what a pin may name -------------------------------------------------

    @Test
    fun `a pin over a single byte is legal`() {
        assertEquals(0L..0L, pin(range = 0L..0L).range)
    }

    @Test
    fun `a pin with a negative start is refused`() {
        val ex = assertThrows(IllegalArgumentException::class.java) { pin(range = -1L..5L) }
        assertTrue(ex.message?.contains("range.first") == true, "message names the field: ${ex.message}")
    }

    @Test
    fun `a pin whose end precedes its start is refused`() {
        assertThrows(IllegalArgumentException::class.java) { pin(range = 5L..4L) }
    }

    @Test
    fun `a pin whose diagnostic contains a newline is refused`() {
        assertThrows(IllegalArgumentException::class.java) { pin(reason = "two\nlines") }
        assertThrows(IllegalArgumentException::class.java) { pin(holder = "two\nlines") }
    }

    @Test
    fun `a pin whose diagnostic exceeds its bound is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            pin(reason = "r".repeat(OutputPin.PIN_REASON_MAX_LEN + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            pin(holder = "h".repeat(OutputPin.PIN_HOLDER_MAX_LEN + 1))
        }
    }

    @Test
    fun `a diagnostic at exactly its bound is accepted`() {
        val atBound = pin(
            reason = "r".repeat(OutputPin.PIN_REASON_MAX_LEN),
            holder = "h".repeat(OutputPin.PIN_HOLDER_MAX_LEN),
        )

        assertEquals(OutputPin.PIN_REASON_MAX_LEN, atBound.reason.length)
    }

    // --- identity -----------------------------------------------------------

    @Test
    fun `two pins over the same range with different holders are different pins`() {
        val a = pin(holder = "inspect-cli")
        val b = pin(holder = "console-tail")

        assertFalse(a == b, "the holder is part of a pin's identity")
    }

    @Test
    fun `a blank pin id is refused`() {
        assertThrows(IllegalArgumentException::class.java) { OutputPinId(" ") }
    }

    @Test
    fun `a generated pin id is a well-formed UUID that satisfies its own guard`() {
        // The one non-deterministic value in the module. What is pinned is its SHAPE and that it
        // passes the same `require` a caller-supplied id does -- not that it repeats, which would
        // need a birthday-bound test to say anything useful.
        val id = OutputPinId.newId()
        val uuid = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")

        assertTrue(uuid.matches(id.value), "the id has the 8-4-4-4-12 shape: ${id.value}")
        assertEquals(OutputPinId(id.value), id, "the value survives the type's own construction")
    }

    @Test
    fun `the pin id prints as its value, not as an object identity`() {
        assertEquals("pin-1", OutputPinId("pin-1").toString())
    }

    // --- the state a pin sits in ---------------------------------------------

    @Test
    fun `an open and a sealed tail at the same offset are different states`() {
        // Same number, different meaning. A `when` over this ADT has to distinguish them, so
        // equality that ignored the type would let one be used where the other belongs.
        assertFalse(OutputTailState.Open(10L) == OutputTailState.Sealed(10L) as OutputTailState)
        assertEquals(OutputTailState.Open(10L), OutputTailState.Open(10L))
    }

    @Test
    fun `a tail state refuses a negative extent`() {
        assertThrows(IllegalArgumentException::class.java) { OutputTailState.Open(-1L) }
        assertThrows(IllegalArgumentException::class.java) { OutputTailState.Sealed(-1L) }
    }

    @Test
    fun `a sealed empty stream is a legal state`() {
        assertEquals(0L, OutputTailState.Sealed(0L).finalEnd)
    }
}
