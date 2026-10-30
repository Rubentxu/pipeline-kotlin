package dev.rubentxu.pipeline.v2.output.store

import dev.rubentxu.pipeline.v2.output.OutputPin
import dev.rubentxu.pipeline.v2.output.OutputPinId
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The guarantee: the record of who holds the bytes is either read whole or not at all.
 *
 * The capacity this serves: a retention pass that consults the pins before it deletes. The consult
 * asks "who holds this stream?" and the answer decides whether bytes somebody is holding survive.
 * A manifest that is read *partially* answers that question wrongly: the pin on the line the reader
 * skipped is a pin nobody is told about, and the bytes it holds are deleted.
 *
 * So a manifest with one malformed line is unreadable in its entirety, and an unreadable manifest
 * is never the same as an empty one. The second half of the guarantee is the encoding: a line that
 * cannot be written unambiguously is refused rather than written as something else.
 *
 * These rows are pure: text in, a manifest out. No file, no store, no clock.
 */
class PinManifestTest {

    @Test
    fun `an empty manifest records no pins`() {
        val manifest = parsePinManifest("")

        val readable = assertInstanceOf(PinManifest.Readable::class.java, manifest)
        assertEquals(
            emptyList<OutputPin>(),
            readable.pins,
            "an empty manifest is a manifest nobody holds anything in",
        )
    }

    @Test
    fun `an encoded pin is read back as the same pin`() {
        val pin = pinOf(stream = "run-1/op-1/stdout", range = 0L..10L)

        val line = encodePinLine(pin)
        assertNotNull(line, "a pin without a TAB encodes")
        val manifest = parsePinManifest(line.orEmpty())

        val readable = assertInstanceOf(PinManifest.Readable::class.java, manifest)
        assertEquals(
            listOf(pin),
            readable.pins,
            "what was written is what is read: the round trip preserves the holder and the range",
        )
    }

    @Test
    fun `a pin without an expiry round trips as no expiry`() {
        val pin = pinOf(stream = "run-1/op-1/stdout", range = 0L..10L, expiresAtMs = null)

        val line = encodePinLine(pin)
        assertNotNull(line, "a pin without an expiry encodes")
        val manifest = parsePinManifest(line.orEmpty())

        val readable = assertInstanceOf(PinManifest.Readable::class.java, manifest)
        assertEquals(
            listOf(pin),
            readable.pins,
            "the absence of an expiry is a fact, not an empty number",
        )
    }

    @Test
    fun `a malformed line makes the whole manifest unreadable`() {
        val good = encodePinLine(pinOf(stream = "run-1/op-1/stdout"))
        assertNotNull(good, "a pin encodes")

        val manifest = parsePinManifest(good.orEmpty() + "\nthis-is-not-a-pin-line")

        assertInstanceOf(
            PinManifest.Unreadable::class.java,
            manifest,
            "one line that does not parse taints the rest: a partial answer is a partial deletion",
        )
    }

    @Test
    fun `an unreadable manifest names the line that failed`() {
        val good = encodePinLine(pinOf(stream = "run-1/op-1/stdout"))
        assertNotNull(good, "a pin encodes")

        val manifest = parsePinManifest(good.orEmpty() + "\nthis-is-not-a-pin-line")

        val unreadable = assertInstanceOf(PinManifest.Unreadable::class.java, manifest)
        assertTrue(
            unreadable.cause.contains("2"),
            "the cause names the line that failed, so the file can be found: ${unreadable.cause}",
        )
    }

    @Test
    fun `a line with the wrong number of fields is unreadable`() {
        val manifest = parsePinManifest("pin-1\trun-1/op-1/stdout\t0\t10\tholder\treason\t0")

        assertInstanceOf(
            PinManifest.Unreadable::class.java,
            manifest,
            "seven fields are not a pin: the missing field is an unknown pin, not an absent one",
        )
    }

    @Test
    fun `a line whose offset is not a number is unreadable`() {
        val manifest = parsePinManifest("pin-1\trun-1/op-1/stdout\tnot-a-number\t10\tholder\treason\t0\t")

        assertInstanceOf(
            PinManifest.Unreadable::class.java,
            manifest,
            "an offset that is not a number does not describe a range",
        )
    }

    @Test
    fun `a line whose range runs backwards is unreadable`() {
        val manifest = parsePinManifest("pin-1\trun-1/op-1/stdout\t10\t0\tholder\treason\t0\t")

        assertInstanceOf(
            PinManifest.Unreadable::class.java,
            manifest,
            "a range that ends before it starts is not a range the pin type accepts",
        )
    }

    @Test
    fun `a reason with a tab is not encodable`() {
        val pin = pinOf(stream = "run-1/op-1/stdout", reason = "held\tfor a read")

        assertNull(
            encodePinLine(pin),
            "a TAB inside the reason would make the line describe something else",
        )
    }

    @Test
    fun `a holder with a tab is not encodable`() {
        val pin = pinOf(stream = "run-1/op-1/stdout", holder = "hold\ter-1")

        assertNull(
            encodePinLine(pin),
            "a TAB inside the holder would make the line describe something else",
        )
    }

    @Test
    fun `a stream with a tab is not encodable`() {
        val pin = pinOf(stream = "run-1\top-1/stdout")

        assertNull(
            encodePinLine(pin),
            "a TAB inside the stream would make the line describe something else",
        )
    }

    private fun pinOf(
        stream: String,
        range: LongRange = 0L..10L,
        holder: String = "holder-1",
        reason: String = "held for a read",
        expiresAtMs: Long? = 1_000L,
    ): OutputPin = OutputPin(
        pinId = OutputPinId("pin-1"),
        stream = OutputStreamId(stream),
        range = range,
        holder = holder,
        reason = reason,
        createdAtMs = 0L,
        expiresAtMs = expiresAtMs,
    )
}
