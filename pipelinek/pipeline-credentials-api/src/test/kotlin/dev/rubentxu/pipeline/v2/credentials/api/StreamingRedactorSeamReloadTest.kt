package dev.rubentxu.pipeline.v2.credentials.api

import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The redactor over a registry that grows WHILE the pass is running.
 *
 * ## The guarantee
 * A value that is registered after the pass captured the seam does not reach the
 * transcript, and the text that surrounds it comes out intact. A value that is split
 * across two fragments stays masked when the seam changes between the two fragments.
 *
 * ## The capacity this serves
 * The channel of a running step is open before the credentials it must mask are known:
 * the binding of a credential is resolved during the execution, and the value is
 * registered when that binding resolves. A pass that captured the seam of the registry
 * and kept it for the whole run masked the values that were known when the channel
 * opened, and nothing else — so the value of a credential that arrived later reached
 * the transcript of the producer in the clear.
 *
 * The fix is not to build the redactor again per fragment: that would lose the state
 * that carries a value split across two fragments, and that state is the reason the
 * redactor exists. The fix is that the registry DECLARES when its seam changed, and the
 * pass reloads it when it sees that — keeping its own state.
 *
 * ## Why these laws assert a shape and not a text
 * What is protected is a shape: the value is not in the clear, it comes out as one
 * marker, and the bytes that are not part of it are still there in their place. The
 * rows below assert that shape, and they say WHY they fail when they fail: a row that
 * fails because the seam was captured before the registration is pointing at the seam,
 * and not at the fragmenting or at the marker.
 */
class StreamingRedactorSeamReloadTest {

    @Test
    @DisplayName("a value registered after the pass started does not reach the transcript")
    fun `a value registered after the pass started does not reach the transcript`() {
        val registry = SecretPatternRegistry()
        val redactor = StreamingRedactor(registry)
        val state = redactor.initialState()

        // The pass starts with an empty registry: the channel is open before the
        // credential that it must mask is known.
        feed(redactor, state, "build ok: ")

        // The registration that reproduces the defect: it happens while the pass runs.
        registry.addSecretValue(VALUE)

        feed(redactor, state, VALUE)
        feed(redactor, state, " and the step finished")

        val transcript = drain(redactor, state)

        assertFalse(
            transcript.contains(VALUE),
            "the value reached the transcript: the seam of the registry was captured before " +
                "the registration, so the pass never reloaded it",
        )
        assertEquals(
            "build ok: $MARKER and the step finished",
            transcript,
            "the value must come out as one marker, and the bytes that are not part of it " +
                "must come out in their place",
        )
    }

    @Test
    @DisplayName("a value registered before the pass started does not reach the transcript either")
    fun `a value registered before the pass started does not reach the transcript either`() {
        // The other half of the promise, and the row that keeps the row above honest:
        // without it, the row above could be measuring something else entirely.
        val registry = SecretPatternRegistry()
        registry.addSecretValue(VALUE)
        val redactor = StreamingRedactor(registry)
        val state = redactor.initialState()

        feed(redactor, state, "build ok: $VALUE and the step finished")

        val transcript = drain(redactor, state)

        assertFalse(
            transcript.contains(VALUE),
            "the value reached the transcript: it was registered before the pass started",
        )
        assertEquals(
            "build ok: $MARKER and the step finished",
            transcript,
            "the value must come out as one marker, and the bytes that are not part of it " +
                "must come out in their place",
        )
    }

    @Test
    @DisplayName("a value split across two fragments stays masked when the seam changes between them")
    fun `a value split across two fragments stays masked when the seam changes between them`() {
        val registry = SecretPatternRegistry()
        registry.addSecretValue(VALUE)
        val redactor = StreamingRedactor(registry)
        val state = redactor.initialState()

        val cut = VALUE.length / 2
        feed(redactor, state, VALUE.substring(0, cut))

        // The seam changes in the middle of the value, and the value that arrives with it
        // is longer than the one in flight: the window of the pass grows, and the bytes
        // that are still pending have to survive that.
        registry.addSecretValue(LONGER_VALUE)

        feed(redactor, state, VALUE.substring(cut))

        val transcript = drain(redactor, state)

        assertFalse(
            transcript.contains(VALUE),
            "the value reached the transcript: the reload threw away the bytes that were " +
                "still pending, and the half of the value that was pending went with them",
        )
        assertEquals(
            MARKER,
            transcript,
            "a value split across a reload must come out as one marker: the bytes that were " +
                "pending were never decided, so the new seam decides them together",
        )
    }

    @Test
    @DisplayName("the bytes that were decided before the reload are still there after it")
    fun `the bytes that were decided before the reload are still there after it`() {
        val registry = SecretPatternRegistry()
        val redactor = StreamingRedactor(registry)
        val state = redactor.initialState()

        feed(redactor, state, "build ok: ")
        val decided = take(redactor, state)

        registry.addSecretValue(VALUE)
        feed(redactor, state, VALUE)

        val transcript = decided + drain(redactor, state)

        assertEquals(
            "build ok: $MARKER",
            transcript,
            "a decided byte is the transcript: a reload cannot un-say it, and it cannot " +
                "make the text around the value disappear",
        )
    }

    /** Feed [text] to the pass. */
    private fun feed(redactor: StreamingRedactor, state: RedactorState, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        redactor.feed(state, bytes, 0, bytes.size)
    }

    /** Take what the pass has decided, and then flush what it still holds. */
    private fun drain(redactor: StreamingRedactor, state: RedactorState): String =
        take(redactor, state) + String(redactor.finish(state), Charsets.UTF_8)

    /** Take what the pass has decided so far. */
    private fun take(redactor: StreamingRedactor, state: RedactorState): String {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(64)
        while (true) {
            val written = redactor.take(state, chunk, 0, chunk.size)
            if (written <= 0) break
            out.write(chunk, 0, written)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private companion object {
        /** The marker a masked value is replaced by. */
        val MARKER = SecretPatternRegistry.SCRUB_MARKER

        /** A value of twenty-four bytes, longer than the threshold in halves. */
        const val VALUE = "s3cret-value-fragment-24"

        /** A value of forty bytes: its window is wider than the one of [VALUE]. */
        const val LONGER_VALUE = "another-credential-value-with-more-bytes"
    }
}
