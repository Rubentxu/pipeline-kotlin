package dev.rubentxu.pipeline.v2.credentials.api

import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The transcript redactor, on its own.
 *
 * ## The guarantee
 * The bytes a run commits are the bytes the child wrote, with the secrets
 * masked and nothing else lost — and that answer does not depend on where the
 * reads happened to be split.
 *
 * ## The capacity this serves
 * A step's transcript is a stream, not a document: the bytes arrive in
 * whatever sizes the pipe hands over, a secret can straddle any boundary
 * between two of them, and the reader wants the bytes that are already
 * decided while the step is still running. This class is the layer that makes
 * that possible, so its guarantees are the ones every consumer of a
 * transcript relies on without asking.
 *
 * ## Why this class needed a test of its own
 * It carries the invariants that are hardest to get right in the product: a
 * secret can straddle any read boundary, the lookahead holds bytes back until
 * they are decidable, and the buffer that holds them is raw bytes straight
 * off a pipe. Every one of those has a defect behind it, and every one was
 * found the hard way — a run that burned thirty-six minutes of CPU, a
 * transcript that came out garbled, a read at the end of a stream that threw
 * away the rest of it. A class like that with no test has already paid for
 * its bugs once and will pay again.
 *
 * ## Why the instrument is this cheap
 * The redactor is a pure state machine over bytes: a registry in, a decision
 * out. There is no clock, no file, no process and no network here, so the
 * cheapest thing that can fail is a direct call — and a defect that only
 * shows up in a 200 MiB run is a defect that should not need one.
 */
class StreamingRedactorTest {

    @Test
    @DisplayName("the transcript is the same however the reads were split")
    fun `the output does not depend on where the reads were split`() {
        val registry = registryOf(SECRET)
        val input = "before $SECRET after"

        // Every possible split, one at a time. The boundary that matters is
        // the one that lands inside the secret, so the whole range is walked
        // instead of a few sizes picked by hand — a size that happens to miss
        // the secret would leave the row green for the wrong reason.
        for (piece in 1..input.length + 1) {
            assertEquals(
                "before **** after",
                redact(registry, input, piece),
                "the transcript changed when the reads were split into pieces of $piece",
            )
        }
    }

    @Test
    @DisplayName("two secrets that share a prefix do not leak the longer one's tail")
    fun `a shared prefix does not leak the tail of the longer secret`() {
        val registry = registryOf("SECRET-AB", "SECRET-ABCDEFGH")
        val input = "SECRET-ABCDEFGH;SECRET-AB;SECRET-ABCDEFGH"

        val transcript = redact(registry, input, 4)

        // The shorter secret used to win the match while the lookahead still
        // held fewer bytes than the longer one, and the longer secret's own
        // tail was then written to the transcript in the clear.
        assertEquals("****;****;****", transcript)
        assertFalse(transcript.contains("CDEFGH"), "the tail of a secret reached the transcript")
    }

    @Test
    @DisplayName("a registry with nothing to mask decides every byte as it arrives")
    fun `an empty registry decides every byte as it arrives`() {
        val registry = SecretPatternRegistry()
        val redactor = StreamingRedactor(registry)
        val state = redactor.initialState()
        val bytes = "ab".toByteArray()

        // The lookahead exists to rule out a secret, and there is none, so the
        // first byte is decided the moment it arrives. A registry that kept
        // the full window anyway would hold bytes it had no reason to hold —
        // and a full lookahead is the state that used to spin.
        redactor.feed(state, bytes, 0, 1)
        redactor.feed(state, bytes, 1, 1)

        assertEquals(
            1,
            redactor.available(state),
            "with nothing to mask the first byte must be decided as soon as it arrives",
        )

        val out = ByteArrayOutputStream()
        takeAll(redactor, state, out)
        out.write(redactor.finish(state))
        assertEquals("ab", out.toString(Charsets.UTF_8.name()), "the bytes must come back untouched")
    }

    @Test
    @DisplayName("the final flush is not truncated by a read that asks for one byte")
    fun `the final flush is not truncated`() {
        val registry = registryOf(SECRET)
        val payload = "tail $SECRET end"
        val stream = StreamingRedactor(registry).wrap(payload.toByteArray().inputStream())

        // A read of one byte at the end of a stream used to hand back the bytes
        // it could hold and throw the rest away: the transcript was truncated
        // by exactly what the caller's buffer could not fit.
        val read = ByteArrayOutputStream()
        val one = ByteArray(1)
        while (true) {
            val n = stream.read(one, 0, 1)
            if (n < 0) break
            read.write(one, 0, n)
        }
        stream.close()

        assertEquals("tail **** end", read.toString(Charsets.UTF_8.name()))
    }

    @Test
    @DisplayName("a feed does not overwrite bytes that were not read yet")
    fun `a large feed does not overwrite bytes that were not read yet`() {
        val registry = registryOf(SECRET)
        val redactor = StreamingRedactor(registry)
        // The queue is sized for what a feed of this size can decide, so a
        // queue sized for the lookahead alone would wrap here and overwrite
        // the bytes the next read had not taken yet.
        val state = redactor.initialState(chunkSize = 256)
        val payload = ("x".repeat(100) + SECRET + "y".repeat(131)).toByteArray()

        redactor.feed(state, payload, 0, payload.size)

        // The queue holds what the feed decided; it is drained in small pieces
        // on purpose, because the bytes that were decided must survive a
        // reader that takes them a few at a time.
        val out = ByteArrayOutputStream()
        takeAll(redactor, state, out)
        out.write(redactor.finish(state))

        val transcript = out.toString(Charsets.UTF_8.name())
        assertEquals("x".repeat(100) + "****" + "y".repeat(131), transcript)
        assertFalse(transcript.contains(SECRET), "the secret reached the transcript")
    }

    @Test
    @DisplayName("a feed that decides more than the queue holds keeps every decided byte")
    fun `a feed larger than the queue keeps every decided byte`() {
        val registry = registryOf(SECRET)
        val redactor = StreamingRedactor(registry)
        // The queue was sized for a feed of the CHUNK size, and a caller may hand
        // over more than that in one call. The bytes such a feed decided ARE the
        // transcript, so they cannot be written over before the reader takes them.
        val state = redactor.initialState(chunkSize = 64)
        val payload = ("x".repeat(2_048) + SECRET + "y".repeat(2_048)).toByteArray()

        redactor.feed(state, payload, 0, payload.size)

        val out = ByteArrayOutputStream()
        takeAll(redactor, state, out)
        out.write(redactor.finish(state))

        assertEquals(
            "x".repeat(2_048) + "****" + "y".repeat(2_048),
            out.toString(Charsets.UTF_8.name()),
            "a decided byte was written over before the reader took it",
        )
    }

    @Test
    @DisplayName("the end of the stream does not overwrite the bytes that were already decided")
    fun `the final flush does not overwrite the bytes that were already decided`() {
        // A secret short enough to leave a small window: what this row is about is
        // the SIZE of what was decided, not the size of the secret. The payload's
        // bytes are all DIFFERENT, so a byte written over shows up as a byte out of
        // place instead of hiding behind an identical neighbour.
        val registry = registryOf(SHORT_SECRET)
        val redactor = StreamingRedactor(registry)
        val state = redactor.initialState(chunkSize = 8)
        val payload = DISTINCT_BYTES.toByteArray()

        redactor.feed(state, payload, 0, payload.size)

        // The caller has NOT taken the decided bytes yet, and that is exactly the
        // moment the end of the stream arrives and the window is flushed.
        val flushed = redactor.finish(state)

        assertEquals(
            DISTINCT_BYTES,
            String(flushed, Charsets.UTF_8),
            "the flush at the end of the stream wrote over the bytes that were already decided",
        )
    }

    @Test
    @DisplayName("a finished transcript keeps no copy of the secret")
    fun `a finished transcript keeps no copy of the secret`() {
        val registry = registryOf(SECRET)
        val redactor = StreamingRedactor(registry)
        val state = redactor.initialState()
        val secret = SECRET.toByteArray()

        redactor.feed(state, secret, 0, secret.size)
        val final = redactor.finish(state)

        // The transcript is clean: the secret was matched, not copied.
        assertEquals(SecretPatternRegistry.SCRUB_MARKER, String(final, Charsets.UTF_8))

        // The lookahead held those very bytes until the match consumed them,
        // and a state the caller is about to drop must not carry them: a
        // dropped object is one the collector keeps for a while.
        assertEquals(0, state.ringCount, "the lookahead must be empty once the transcript is finished")
        assertFalse(
            state.ring.toList().any { it.toInt() != 0 },
            "the lookahead still holds the bytes of a secret the transcript already masked",
        )
        assertFalse(
            state.outputQueue.toList().any { it.toInt() != 0 },
            "the output queue still holds bytes after the transcript was finished",
        )
    }

    @Test
    @DisplayName("a read hands back what it decided without asking the source for more")
    fun `a read returns what it decided without asking for more`() {
        val registry = registryOf(SECRET)
        val payload = SECRET + "x".repeat(4_000)
        val source = OneShotSource(payload.toByteArray())
        val stream = StreamingRedactor(registry).wrap(source)

        val buffer = ByteArray(64 * 1024)
        val n = stream.read(buffer, 0, buffer.size)

        // The source is read once and only once. A reader that had to ask for
        // more before handing back what it had already decided would wait for
        // a producer that has nothing more to say, and the transcript of a
        // running step would arrive only when the step ended.
        assertEquals(1, source.reads, "the reader asked the source for more than it needed")
        assertTrue(n > 0, "the read decided nothing")

        // What comes back is the beginning of the redacted transcript: the
        // secret is masked and not one decided byte is lost. The tail is still
        // in the lookahead, which is where it belongs — the reader was given
        // what was decided, not what was still waiting to be decided.
        val transcript = String(buffer, 0, n, Charsets.UTF_8)
        val expected = SecretPatternRegistry.SCRUB_MARKER + "x".repeat(4_000)
        assertTrue(
            expected.startsWith(transcript),
            "the bytes the reader got back are not the transcript the child wrote",
        )
        assertFalse(transcript.contains(SECRET), "the secret reached the transcript")
        stream.close()
    }

    private fun registryOf(vararg secrets: String): SecretPatternRegistry {
        val registry = SecretPatternRegistry()
        secrets.forEach { registry.addSecretValue(it) }
        return registry
    }

    /**
     * Feed [input] to the redactor in pieces of [pieceSize] bytes and return
     * everything it decided, in order.
     */
    private fun redact(registry: SecretPatternRegistry, input: String, pieceSize: Int): String {
        val redactor = StreamingRedactor(registry)
        val state = redactor.initialState()
        val bytes = input.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()

        var offset = 0
        while (offset < bytes.size) {
            val len = minOf(pieceSize, bytes.size - offset)
            redactor.feed(state, bytes, offset, len)
            offset += len
            takeAll(redactor, state, out)
        }
        out.write(redactor.finish(state))
        return out.toString(Charsets.UTF_8.name())
    }

    /** Move everything the state has decided into [out]. */
    private fun takeAll(redactor: StreamingRedactor, state: RedactorState, out: ByteArrayOutputStream) {
        val chunk = ByteArray(64)
        while (true) {
            val n = redactor.take(state, chunk, 0, chunk.size)
            if (n <= 0) break
            out.write(chunk, 0, n)
        }
    }

    /**
     * A source that has one answer and refuses to be asked twice.
     *
     * A reader that must not wait for more data fails here instead of hanging,
     * which is the difference between a red row and a test that never ends.
     */
    private class OneShotSource(private val payload: ByteArray) : InputStream() {
        var reads = 0
            private set

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n > 0) one[0].toInt() and 0xFF else -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            reads++
            require(reads == 1) { "the source was asked for more data than it has" }
            val n = minOf(len, payload.size)
            System.arraycopy(payload, 0, b, off, n)
            return n
        }
    }

    private companion object {
        /** Long enough to be maskable: the registry ignores anything shorter. */
        const val SECRET = "e2e-fixture-secret-value"

        /** The shortest secret the registry accepts, for the smallest possible window. */
        const val SHORT_SECRET = "e2efixt1"

        /** Thirty different bytes, so a byte out of place cannot hide. */
        const val DISTINCT_BYTES = "abcdefghijklmnopqrstuvwxyzABCD"
    }
}
