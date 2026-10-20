package dev.rubentxu.pipeline.v2.events.identity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The wire contract of [EventCursor]: a continuation token a client encodes, hands to the engine,
 * and gets back later to resume reading.
 * The guarantee is not "the format looks right" but the round trip: whatever this encoder emits,
 * this decoder must accept, and must recover the same run and the same sequence. A token that
 * survives neither direction is not a continuation, it is a dead end -- and the caller cannot tell
 * the difference, because a rejected token and a fresh request both arrive as "no events".
 * This defect ALREADY HAPPENED HERE. `encode()` interpolated the run id raw while `decode()` split
 * on colons and demanded exactly three parts, so any run id containing a colon produced a token its
 * own decoder refused. And a run id built the ordinary way does contain colons: a `ResourceRef`
 * canonical text is `v<version>:<kind>:<segments>`. The first version of this pair therefore
 * round-tripped only for hand-written run ids that no producer emits. It surfaced in an independent
 * consumer build that could not decode the token it had just encoded.
 * The guardian for that fix was deleted in `2aa071aab` while the KDoc describing the fix stayed.
 * This file is the guardian the KDoc already implied.
 * Why the E2E layer cannot cover it: `examples/run.sh` reads the process exit code and, at most, a
 * substring of stderr. The cursor travels in the API response on stdout, which the launcher writes
 * to a file and never opens. A token that no longer round-trips changes neither of the two facts
 * the launcher observes -- so a regression here is invisible to the entire executable suite.
*/
class EventCursorTest {

    /** The shape a real producer emits: a versioned ResourceRef canonical text. */
    private val runIdWithColons = "v1:fabric:contractvalidated/abc"

    @Test
    fun `a run id containing colons survives the round trip`() {
        // THE ROW. Encode the id raw and this returns null: the token splits into
        // five parts instead of three, and the refusal is indistinguishable from an unknown token.
        val cursor = EventCursor(runIdWithColons, 42L)

        val decoded = requireNotNull(EventCursor.decode(cursor.encode())) {
            "the encoder produced a token its own decoder rejected"
        }

        assertEquals(runIdWithColons, decoded.runId)
        assertEquals(42L, decoded.lastSequence)
    }

    @Test
    fun `a plain run id survives the round trip`() {
        // The other direction. An escaping scheme that only worked for ids with colons would be a
        // different bug with the same shape, so the round trip is stated for both inputs.
        val cursor = EventCursor("run-1234", 0L)

        assertEquals(cursor, EventCursor.decode(cursor.encode()))
    }

    @Test
    fun `a zero sequence survives the round trip`() {
        // Stated because zero is a real sequence on a real run -- it is what a cursor carries
        // before the first event is recorded -- and an encoder that formatted the number
        // conditionally would drop a field exactly here.
        val cursor = EventCursor("run-1234", 0L)

        assertEquals("0", EventCursor.decode(cursor.encode())?.lastSequence?.toString())
    }

    @Test
    fun `a large sequence survives without truncation`() {
        // The token is text, and text that goes through a numeric parse is where a Long stops
        // being a Long. A narrowed format would silently cap the resumable window.
        val cursor = EventCursor("run-1234", Long.MAX_VALUE)

        assertEquals(Long.MAX_VALUE, EventCursor.decode(cursor.encode())?.lastSequence)
    }

    @Test
    fun `a run id containing a slash survives the round trip`() {
        // Canonical text joins segments with slashes, so this is the other character the real form
        // carries, and the token escapes it as `%2F` rather than leaving it bare.
        // MEASURED, and worth saying plainly: this row is NOT an independent guard. Removing the
        // escaping turns this one red for the same reason it turns the colon row red -- the run id
        // in it also contains colons, so the token splits into five parts. A slash on its own would
        // not fail at all, because `:` is what the decoder splits on. The row earns its place by
        // pinning that slashes are escaped too, which is a property of the wire format an
        // independent consumer parses; it does not earn a claim of extra discriminance.
        val cursor = EventCursor("v1:fabric:a/b/c", 7L)

        assertEquals(cursor, EventCursor.decode(cursor.encode()))
    }

    // --- refusals: an unusable token is refused, not guessed -------------------------------

    @Test
    fun `a token with the wrong prefix is refused`() {
        // Another plane's token. The engine has more than one cursor family and they must not
        // decode into each other: a token that decoded to a DIFFERENT run would be read as
        // progress and skip history.
        assertNull(EventCursor.decode("evt-cursor-v2:run-1:5"))
        assertNull(EventCursor.decode("run-1:5"))
        assertNull(EventCursor.decode(":run-1:5"))
    }

    @Test
    fun `a token with the wrong number of parts is refused`() {
        // Both directions of the shape error: too few (a hand-written token) and too many (the
        // unescaped-run-id token this very class used to produce).
        assertNull(EventCursor.decode("evt-cursor-v1:run-1"))
        assertNull(EventCursor.decode("evt-cursor-v1:run-1:5:extra"))
        assertNull(EventCursor.decode("evt-cursor-v1:v1:fabric:thing:5"))
    }

    @Test
    fun `a token whose sequence is not a number is refused`() {
        // Refused rather than coerced to zero. Coercing would resume from the beginning of a run
        // the caller believed it had already read, which is silent history loss.
        assertNull(EventCursor.decode("evt-cursor-v1:run-1:abc"))
        assertNull(EventCursor.decode("evt-cursor-v1:run-1:"))
        assertNull(EventCursor.decode("evt-cursor-v1:run-1:1.5"))
    }

    @Test
    fun `a token with a blank run id is refused`() {
        // The one case where the token round-trips mechanically but must still not be accepted:
        // an empty run id names no run, and a cursor with no run is not a resumable position.
        assertNull(EventCursor.decode("evt-cursor-v1::5"))
        assertNull(EventCursor.decode("evt-cursor-v1:%20:5"))
    }

    @Test
    fun `an empty token is refused`() {
        // Stated on its own because `split` on an empty string yields a one-element list, and a
        // bounds check written before the shape check would throw instead of refusing.
        assertNull(EventCursor.decode(""))
    }
}
